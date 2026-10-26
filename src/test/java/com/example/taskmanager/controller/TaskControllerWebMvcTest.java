package com.example.taskmanager.controller;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.taskmanager.dto.TaskRequest;
import com.example.taskmanager.dto.TaskResponse;
import com.example.taskmanager.entity.TaskStatus;
import com.example.taskmanager.exception.TaskNotFoundException;
import com.example.taskmanager.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

/**
 * Web-layer slice test: only the controller, validation and exception handler are loaded.
 * The service is replaced by a Mockito mock, so no database is involved.
 */
@WebMvcTest(TaskController.class)
@DisplayName("TaskController web layer tests")
class TaskControllerWebMvcTest {

    private static final String BASE_URL = "/api/tasks";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    @Test
    @DisplayName("POST returns 201, a Location header and the created task")
    void createTask_validRequest_returns201() throws Exception {
        when(taskService.createTask(any(TaskRequest.class)))
                .thenReturn(sampleResponse(5L, "Write docs", TaskStatus.TODO));

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskRequest("Write docs", "Details", null))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/tasks/5")))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.title").value("Write docs"))
                .andExpect(jsonPath("$.status").value("TODO"));
    }

    @Test
    @DisplayName("POST with a blank title returns 400 with field errors and never calls the service")
    void createTask_blankTitle_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \",\"description\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value(BASE_URL))
                .andExpect(jsonPath("$.validationErrors.title").value("Title must not be blank"));

        verify(taskService, never()).createTask(any(TaskRequest.class));
    }

    @Test
    @DisplayName("POST with malformed JSON returns 400")
    void createTask_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"unterminated"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid field value"))
                .andExpect(jsonPath("$.validationErrors").doesNotExist());
    }

    @Test
    @DisplayName("POST with an unknown status value returns 400")
    void createTask_unknownStatus_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Task\",\"status\":\"DONE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("GET all returns 200 and a JSON array")
    void getAllTasks_returns200() throws Exception {
        when(taskService.getAllTasks()).thenReturn(List.of(
                sampleResponse(1L, "One", TaskStatus.TODO),
                sampleResponse(2L, "Two", TaskStatus.COMPLETED)));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET by id returns 200 for an existing task")
    void getTask_existing_returns200() throws Exception {
        when(taskService.getTaskById(1L)).thenReturn(sampleResponse(1L, "One", TaskStatus.TODO));

        mockMvc.perform(get(BASE_URL + "/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("GET by id returns 404 with the standard error structure")
    void getTask_missing_returns404WithErrorStructure() throws Exception {
        when(taskService.getTaskById(99L)).thenThrow(new TaskNotFoundException(99L));

        mockMvc.perform(get(BASE_URL + "/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Task not found with id: 99"))
                .andExpect(jsonPath("$.path").value(BASE_URL + "/99"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("GET with a non-numeric id returns 400")
    void getTask_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get(BASE_URL + "/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'id'"));
    }

    @Test
    @DisplayName("PUT returns 200 with the updated task")
    void updateTask_existing_returns200() throws Exception {
        when(taskService.updateTask(eq(1L), any(TaskRequest.class)))
                .thenReturn(sampleResponse(1L, "Updated", TaskStatus.IN_PROGRESS));

        mockMvc.perform(put(BASE_URL + "/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskRequest("Updated", "d", TaskStatus.IN_PROGRESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("PUT returns 404 when the task does not exist")
    void updateTask_missing_returns404() throws Exception {
        when(taskService.updateTask(eq(99L), any(TaskRequest.class))).thenThrow(new TaskNotFoundException(99L));

        mockMvc.perform(put(BASE_URL + "/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(new TaskRequest("Updated", null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH status returns 200 with the new status")
    void updateTaskStatus_existing_returns200() throws Exception {
        when(taskService.updateTaskStatus(1L, TaskStatus.COMPLETED))
                .thenReturn(sampleResponse(1L, "One", TaskStatus.COMPLETED));

        mockMvc.perform(patch(BASE_URL + "/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("PATCH status without a status returns 400")
    void updateTaskStatus_missingStatus_returns400() throws Exception {
        mockMvc.perform(patch(BASE_URL + "/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.status").value("Status is required"));
    }

    @Test
    @DisplayName("DELETE returns 204 with no body")
    void deleteTask_existing_returns204() throws Exception {
        mockMvc.perform(delete(BASE_URL + "/1"))
                .andExpect(status().isNoContent());

        verify(taskService).deleteTask(1L);
    }

    @Test
    @DisplayName("DELETE returns 404 when the task does not exist")
    void deleteTask_missing_returns404() throws Exception {
        doThrow(new TaskNotFoundException(99L)).when(taskService).deleteTask(99L);

        mockMvc.perform(delete(BASE_URL + "/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Repository outage surfaces as 503 without leaking internals")
    void getAllTasks_databaseFailure_returns503() throws Exception {
        when(taskService.getAllTasks()).thenThrow(new DataAccessResourceFailureException("jdbc:secret-detail"));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The service is temporarily unavailable. Please try again later"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("Unexpected errors surface as a generic 500")
    void getAllTasks_unexpectedFailure_returns500() throws Exception {
        when(taskService.getAllTasks()).thenThrow(new IllegalStateException("internal detail"));

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("Unsupported HTTP method returns 405 in the same JSON structure")
    void unsupportedMethod_returns405() throws Exception {
        mockMvc.perform(put(BASE_URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static TaskResponse sampleResponse(long id, String title, TaskStatus status) {
        return new TaskResponse(id, title, "Sample description", status, NOW, NOW);
    }
}
