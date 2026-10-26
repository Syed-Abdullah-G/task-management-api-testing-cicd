package com.example.taskmanager.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.taskmanager.entity.Task;
import com.example.taskmanager.entity.TaskStatus;
import com.example.taskmanager.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

/**
 * Full-stack integration tests: real controller, service, repository and an in-memory H2 database.
 * Named *IT so Maven Failsafe runs them in the `verify` phase, separately from the unit tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Task API integration tests")
class TaskApiIT {

    private static final String BASE_URL = "/api/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void cleanDatabase() {
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("Create a task: 201, generated id, default TODO status, persisted")
    void createTask_persistsAndReturns201() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", "Learn MockMvc", "description", "Integration style"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Learn MockMvc"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());

        assertEquals(1, taskRepository.count());
    }

    @Test
    @DisplayName("Retrieve a task by id")
    void getTask_existing_returns200() throws Exception {
        long id = createTaskViaApi("Read me", "Some description");

        mockMvc.perform(get(BASE_URL + "/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Read me"))
                .andExpect(jsonPath("$.description").value("Some description"));
    }

    @Test
    @DisplayName("Retrieve all tasks")
    void getAllTasks_returnsEveryStoredTask() throws Exception {
        createTaskViaApi("First", null);
        createTaskViaApi("Second", null);

        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("Retrieve all tasks when none exist returns an empty array")
    void getAllTasks_empty_returnsEmptyArray() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Update task details with PUT")
    void updateTask_changesStoredValues() throws Exception {
        long id = createTaskViaApi("Old title", "Old description");

        mockMvc.perform(put(BASE_URL + "/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "title", "New title",
                                "description", "New description",
                                "status", "IN_PROGRESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        Optional<Task> stored = taskRepository.findById(id);
        assertTrue(stored.isPresent());
        assertEquals("New title", stored.get().getTitle());
        assertEquals("New description", stored.get().getDescription());
    }

    @Test
    @DisplayName("Change task status with PATCH")
    void updateTaskStatus_changesStoredStatus() throws Exception {
        long id = createTaskViaApi("Finish me", null);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "COMPLETED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertEquals(TaskStatus.COMPLETED, taskRepository.findById(id).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("Delete a task: 204, then it is gone")
    void deleteTask_removesTask() throws Exception {
        long id = createTaskViaApi("Delete me", null);

        mockMvc.perform(delete(BASE_URL + "/" + id))
                .andExpect(status().isNoContent());

        assertFalse(taskRepository.existsById(id));
        mockMvc.perform(get(BASE_URL + "/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Missing task returns 404 for every id-based endpoint")
    void missingTask_returns404ForAllEndpoints() throws Exception {
        String url = BASE_URL + "/9999";
        String validBody = body(Map.of("title", "Anything"));

        mockMvc.perform(get(url)).andExpect(status().isNotFound());
        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(validBody))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(url + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "COMPLETED"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(url)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("404 response has the standard error structure and no stack trace")
    void errorResponse_hasConsistentStructure() throws Exception {
        mockMvc.perform(get(BASE_URL + "/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Task not found with id: 9999"))
                .andExpect(jsonPath("$.path").value(BASE_URL + "/9999"))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    @DisplayName("Blank title returns 400 and nothing is stored")
    void createTask_blankTitle_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value("Title must not be blank"));

        assertEquals(0, taskRepository.count());
    }

    @Test
    @DisplayName("Boundary: title longer than the limit returns 400")
    void createTask_titleTooLong_returns400() throws Exception {
        String tooLong = "a".repeat(Task.MAX_TITLE_LENGTH + 1);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", tooLong))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").exists());
    }

    @Test
    @DisplayName("Boundary: title of exactly the maximum length is accepted")
    void createTask_titleAtLimit_returns201() throws Exception {
        String atLimit = "a".repeat(Task.MAX_TITLE_LENGTH);

        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", atLimit))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Malformed JSON returns 400")
    void createTask_malformedJson_returns400() throws Exception {
        mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request or invalid field value"));
    }

    @Test
    @DisplayName("Invalid status value in PATCH returns 400")
    void updateTaskStatus_invalidStatus_returns400() throws Exception {
        long id = createTaskViaApi("Task", null);

        mockMvc.perform(patch(BASE_URL + "/" + id + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "NOT_A_STATUS"))))
                .andExpect(status().isBadRequest());

        assertEquals(TaskStatus.TODO, taskRepository.findById(id).orElseThrow().getStatus());
    }

    private long createTaskViaApi(String title, String description) throws Exception {
        Map<String, String> payload = description == null
                ? Map.of("title", title)
                : Map.of("title", title, "description", description);

        String response = mockMvc.perform(post(BASE_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(payload)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("id").asLong();
    }

    private String body(Map<String, String> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }
}
