package com.example.taskmanager.dto;

import com.example.taskmanager.entity.Task;
import com.example.taskmanager.entity.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating or fully updating a task.
 * {@code status} is optional: a new task defaults to TODO, an update keeps the current status.
 */
public record TaskRequest(
        @NotBlank(message = "Title must not be blank")
        @Size(max = Task.MAX_TITLE_LENGTH, message = "Title must be at most {max} characters")
        String title,

        @Size(max = Task.MAX_DESCRIPTION_LENGTH, message = "Description must be at most {max} characters")
        String description,

        TaskStatus status) {
}
