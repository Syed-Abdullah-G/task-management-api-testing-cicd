package com.example.taskmanager.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.example.taskmanager.dto.TaskRequest;
import com.example.taskmanager.dto.TaskResponse;
import com.example.taskmanager.entity.Task;
import com.example.taskmanager.entity.TaskStatus;
import com.example.taskmanager.exception.InvalidTaskException;
import com.example.taskmanager.exception.TaskNotFoundException;
import com.example.taskmanager.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

/**
 * Pure unit tests: the repository is mocked, so no Spring context and no database are involved.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaskService unit tests")
class TaskServiceTest {

    private static final long EXISTING_ID = 1L;
    private static final long MISSING_ID = 99L;

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskService taskService;

    private Task existingTask;

    @BeforeEach
    void setUp() {
        existingTask = new Task("Write tests", "Cover the service layer", TaskStatus.TODO);
        existingTask.setId(EXISTING_ID);
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("createTask saves a trimmed task with the default TODO status")
    void createTask_validRequest_savesTaskWithDefaultStatus() {
        // Arrange
        TaskRequest request = new TaskRequest("  Write docs  ", "Document the API", null);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> {
            Task toSave = invocation.getArgument(0);
            toSave.setId(10L);
            return toSave;
        });

        // Act
        TaskResponse response = taskService.createTask(request);

        // Assert
        assertNotNull(response);
        assertEquals(10L, response.id());
        assertEquals("Write docs", response.title());
        assertEquals(TaskStatus.TODO, response.status());

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, times(1)).save(captor.capture());
        assertEquals("Write docs", captor.getValue().getTitle());
        assertEquals("Document the API", captor.getValue().getDescription());
        assertEquals(TaskStatus.TODO, captor.getValue().getStatus());
    }

    @ParameterizedTest(name = "createTask keeps explicit status {0}")
    @EnumSource(TaskStatus.class)
    void createTask_explicitStatus_isPreserved(TaskStatus status) {
        // Arrange
        TaskRequest request = new TaskRequest("Task", null, status);
        stubSaveToReturnArgument();

        // Act
        TaskResponse response = taskService.createTask(request);

        // Assert
        assertEquals(status, response.status());
    }

    @ParameterizedTest(name = "createTask rejects title [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void createTask_blankTitle_throwsAndDoesNotTouchRepository(String title) {
        // Arrange
        TaskRequest request = new TaskRequest(title, "description", null);

        // Act
        InvalidTaskException exception =
                assertThrows(InvalidTaskException.class, () -> taskService.createTask(request));

        // Assert
        assertEquals("Title must not be blank", exception.getMessage());
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("createTask rejects a null request")
    void createTask_nullRequest_throwsAndDoesNotTouchRepository() {
        // Act + Assert
        assertThrows(InvalidTaskException.class, () -> taskService.createTask(null));
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("boundary: a title of exactly the maximum length is accepted")
    void createTask_titleAtMaxLength_isAccepted() {
        // Arrange
        String title = "a".repeat(Task.MAX_TITLE_LENGTH);
        stubSaveToReturnArgument();

        // Act
        TaskResponse response = taskService.createTask(new TaskRequest(title, null, null));

        // Assert
        assertEquals(Task.MAX_TITLE_LENGTH, response.title().length());
    }

    @Test
    @DisplayName("boundary: a title one character too long is rejected")
    void createTask_titleOverMaxLength_isRejected() {
        // Arrange
        TaskRequest request = new TaskRequest("a".repeat(Task.MAX_TITLE_LENGTH + 1), null, null);

        // Act + Assert
        assertThrows(InvalidTaskException.class, () -> taskService.createTask(request));
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    @DisplayName("boundary: a description of exactly the maximum length is accepted")
    void createTask_descriptionAtMaxLength_isAccepted() {
        // Arrange
        String description = "d".repeat(Task.MAX_DESCRIPTION_LENGTH);
        stubSaveToReturnArgument();

        // Act
        TaskResponse response = taskService.createTask(new TaskRequest("Title", description, null));

        // Assert
        assertEquals(Task.MAX_DESCRIPTION_LENGTH, response.description().length());
    }

    @Test
    @DisplayName("boundary: a description one character too long is rejected")
    void createTask_descriptionOverMaxLength_isRejected() {
        // Arrange
        TaskRequest request = new TaskRequest("Title", "d".repeat(Task.MAX_DESCRIPTION_LENGTH + 1), null);

        // Act + Assert
        assertThrows(InvalidTaskException.class, () -> taskService.createTask(request));
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("createTask propagates repository failures")
    void createTask_repositoryFailure_propagatesDataAccessException() {
        // Arrange
        TaskRequest request = new TaskRequest("Title", "description", null);
        when(taskRepository.save(any(Task.class)))
                .thenThrow(new DataAccessResourceFailureException("Database unavailable"));

        // Act + Assert
        assertThrows(DataAccessException.class, () -> taskService.createTask(request));
        verify(taskRepository, times(1)).save(any(Task.class));
        verifyNoMoreInteractions(taskRepository);
    }

    // -------------------------------------------------------------- retrieve

    @Test
    @DisplayName("getTaskById returns the task when it exists")
    void getTaskById_existingTask_returnsResponse() {
        // Arrange
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));

        // Act
        TaskResponse response = taskService.getTaskById(EXISTING_ID);

        // Assert
        assertNotNull(response);
        assertEquals(EXISTING_ID, response.id());
        assertEquals("Write tests", response.title());
        assertEquals(TaskStatus.TODO, response.status());
        verify(taskRepository, times(1)).findById(EXISTING_ID);
    }

    @Test
    @DisplayName("getTaskById throws TaskNotFoundException when the task is missing")
    void getTaskById_missingTask_throwsTaskNotFoundException() {
        // Arrange
        when(taskRepository.findById(MISSING_ID)).thenReturn(Optional.empty());

        // Act
        TaskNotFoundException exception =
                assertThrows(TaskNotFoundException.class, () -> taskService.getTaskById(MISSING_ID));

        // Assert
        assertTrue(exception.getMessage().contains(String.valueOf(MISSING_ID)));
        verify(taskRepository, times(1)).findById(MISSING_ID);
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    @DisplayName("getAllTasks maps every stored task")
    void getAllTasks_tasksExist_returnsAllOfThem() {
        // Arrange
        Task second = new Task("Second", null, TaskStatus.COMPLETED);
        second.setId(2L);
        when(taskRepository.findAll()).thenReturn(List.of(existingTask, second));

        // Act
        List<TaskResponse> responses = taskService.getAllTasks();

        // Assert
        assertFalse(responses.isEmpty());
        assertEquals(2, responses.size());
        assertEquals("Second", responses.get(1).title());
    }

    @Test
    @DisplayName("getAllTasks returns an empty list when nothing is stored")
    void getAllTasks_noTasks_returnsEmptyList() {
        // Arrange
        when(taskRepository.findAll()).thenReturn(List.of());

        // Act
        List<TaskResponse> responses = taskService.getAllTasks();

        // Assert
        assertTrue(responses.isEmpty());
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("updateTask changes title, description and status, then saves")
    void updateTask_existingTask_updatesFieldsAndSaves() {
        // Arrange
        TaskRequest request = new TaskRequest(" Updated title ", "Updated description", TaskStatus.IN_PROGRESS);
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));
        when(taskRepository.save(existingTask)).thenReturn(existingTask);

        // Act
        TaskResponse response = taskService.updateTask(EXISTING_ID, request);

        // Assert
        assertEquals("Updated title", response.title());
        assertEquals("Updated description", response.description());
        assertEquals(TaskStatus.IN_PROGRESS, response.status());

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, times(1)).save(captor.capture());
        assertEquals("Updated title", captor.getValue().getTitle());
    }

    @Test
    @DisplayName("updateTask keeps the current status when the request has none")
    void updateTask_withoutStatus_keepsExistingStatus() {
        // Arrange
        existingTask.setStatus(TaskStatus.IN_PROGRESS);
        TaskRequest request = new TaskRequest("New title", null, null);
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));
        when(taskRepository.save(existingTask)).thenReturn(existingTask);

        // Act
        TaskResponse response = taskService.updateTask(EXISTING_ID, request);

        // Assert
        assertEquals(TaskStatus.IN_PROGRESS, response.status());
    }

    @Test
    @DisplayName("updateTask on a missing task throws and never saves")
    void updateTask_missingTask_throwsAndDoesNotSave() {
        // Arrange
        when(taskRepository.findById(MISSING_ID)).thenReturn(Optional.empty());
        TaskRequest request = new TaskRequest("Title", null, null);

        // Act + Assert
        assertThrows(TaskNotFoundException.class, () -> taskService.updateTask(MISSING_ID, request));
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    @DisplayName("updateTask with invalid input fails before any repository call")
    void updateTask_invalidInput_doesNotTouchRepository() {
        // Arrange
        TaskRequest request = new TaskRequest("   ", null, null);

        // Act + Assert
        assertThrows(InvalidTaskException.class, () -> taskService.updateTask(EXISTING_ID, request));
        verifyNoInteractions(taskRepository);
    }

    // ---------------------------------------------------------- status change

    @ParameterizedTest(name = "updateTaskStatus applies {0}")
    @EnumSource(TaskStatus.class)
    void updateTaskStatus_existingTask_appliesNewStatus(TaskStatus newStatus) {
        // Arrange
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));
        when(taskRepository.save(existingTask)).thenReturn(existingTask);

        // Act
        TaskResponse response = taskService.updateTaskStatus(EXISTING_ID, newStatus);

        // Assert
        assertEquals(newStatus, response.status());
        verify(taskRepository, times(1)).save(existingTask);
    }

    @Test
    @DisplayName("updateTaskStatus rejects a null status without touching the repository")
    void updateTaskStatus_nullStatus_throwsAndDoesNotTouchRepository() {
        // Act + Assert
        assertThrows(InvalidTaskException.class, () -> taskService.updateTaskStatus(EXISTING_ID, null));
        verifyNoInteractions(taskRepository);
    }

    @Test
    @DisplayName("updateTaskStatus on a missing task throws and never saves")
    void updateTaskStatus_missingTask_throwsAndDoesNotSave() {
        // Arrange
        when(taskRepository.findById(MISSING_ID)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(TaskNotFoundException.class,
                () -> taskService.updateTaskStatus(MISSING_ID, TaskStatus.COMPLETED));
        verify(taskRepository, never()).save(any(Task.class));
    }

    // ---------------------------------------------------------------- delete

    @Test
    @DisplayName("deleteTask deletes an existing task")
    void deleteTask_existingTask_deletesIt() {
        // Arrange
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));

        // Act
        taskService.deleteTask(EXISTING_ID);

        // Assert
        verify(taskRepository, times(1)).delete(existingTask);
    }

    @Test
    @DisplayName("deleteTask on a missing task throws and never calls delete")
    void deleteTask_missingTask_throwsAndDoesNotDelete() {
        // Arrange
        when(taskRepository.findById(MISSING_ID)).thenReturn(Optional.empty());

        // Act + Assert
        assertThrows(TaskNotFoundException.class, () -> taskService.deleteTask(MISSING_ID));
        verify(taskRepository, never()).delete(any(Task.class));
    }

    @Test
    @DisplayName("deleteTask propagates a failure thrown by the void repository method")
    void deleteTask_repositoryFailure_propagatesDataAccessException() {
        // Arrange
        when(taskRepository.findById(EXISTING_ID)).thenReturn(Optional.of(existingTask));
        doThrow(new DataAccessResourceFailureException("Database unavailable"))
                .when(taskRepository).delete(existingTask);

        // Act + Assert
        assertThrows(DataAccessException.class, () -> taskService.deleteTask(EXISTING_ID));
        verify(taskRepository, times(1)).delete(existingTask);
    }

    // --------------------------------------------------------------- helpers

    private void stubSaveToReturnArgument() {
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }
}
