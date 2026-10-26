package com.example.taskmanager.service;

import com.example.taskmanager.dto.TaskRequest;
import com.example.taskmanager.dto.TaskResponse;
import com.example.taskmanager.entity.Task;
import com.example.taskmanager.entity.TaskStatus;
import com.example.taskmanager.exception.InvalidTaskException;
import com.example.taskmanager.exception.TaskNotFoundException;
import com.example.taskmanager.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Business logic for tasks. Throws domain exceptions and lets repository failures propagate;
 * logging of failures is done once, in the GlobalExceptionHandler.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository taskRepository;

    public TaskService(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional
    public TaskResponse createTask(TaskRequest request) {
        validate(request);
        TaskStatus status = request.status() == null ? TaskStatus.TODO : request.status();
        Task task = new Task(request.title().trim(), request.description(), status);

        Task saved = taskRepository.save(task);
        log.info("Created task id={} status={}", saved.getId(), saved.getStatus());
        return TaskResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getAllTasks() {
        return taskRepository.findAll().stream()
                .map(TaskResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse getTaskById(Long id) {
        return TaskResponse.from(findTaskOrThrow(id));
    }

    @Transactional
    public TaskResponse updateTask(Long id, TaskRequest request) {
        validate(request);
        Task task = findTaskOrThrow(id);

        task.setTitle(request.title().trim());
        task.setDescription(request.description());
        if (request.status() != null) {
            task.setStatus(request.status());
        }

        Task saved = taskRepository.save(task);
        log.info("Updated task id={}", saved.getId());
        return TaskResponse.from(saved);
    }

    @Transactional
    public TaskResponse updateTaskStatus(Long id, TaskStatus newStatus) {
        if (newStatus == null) {
            throw new InvalidTaskException("Status is required");
        }
        Task task = findTaskOrThrow(id);
        task.setStatus(newStatus);

        Task saved = taskRepository.save(task);
        log.info("Changed status of task id={} to {}", saved.getId(), saved.getStatus());
        return TaskResponse.from(saved);
    }

    @Transactional
    public void deleteTask(Long id) {
        Task task = findTaskOrThrow(id);
        taskRepository.delete(task);
        log.info("Deleted task id={}", id);
    }

    private Task findTaskOrThrow(Long id) {
        return taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    private static void validate(TaskRequest request) {
        if (request == null) {
            throw new InvalidTaskException("Task request must not be null");
        }
        String title = request.title();
        if (title == null || title.isBlank()) {
            throw new InvalidTaskException("Title must not be blank");
        }
        if (title.length() > Task.MAX_TITLE_LENGTH) {
            throw new InvalidTaskException("Title must be at most " + Task.MAX_TITLE_LENGTH + " characters");
        }
        String description = request.description();
        if (description != null && description.length() > Task.MAX_DESCRIPTION_LENGTH) {
            throw new InvalidTaskException(
                    "Description must be at most " + Task.MAX_DESCRIPTION_LENGTH + " characters");
        }
    }
}
