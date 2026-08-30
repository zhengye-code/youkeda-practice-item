package com.claw.assistant.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class FileAgentTaskRepository implements AgentTaskRepository {
    private final Path storeDirectory;
    private final ObjectMapper objectMapper;

    @Autowired
    public FileAgentTaskRepository(@Value("${agent.task.store-dir:data/agent-tasks}") String storeDirectory) {
        this(Path.of(storeDirectory), new ObjectMapper().findAndRegisterModules());
    }

    FileAgentTaskRepository(Path storeDirectory, ObjectMapper objectMapper) {
        this.storeDirectory = storeDirectory;
        this.objectMapper = configureTimeModule(objectMapper);
    }

    @Override
    public synchronized AgentTask save(AgentTask task) throws IOException {
        Files.createDirectories(storeDirectory);
        Path target = taskPath(task.id());
        Path temporary = Files.createTempFile(storeDirectory, task.id() + "-", ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), task);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return task;
    }

    @Override
    public synchronized Optional<AgentTask> findById(String taskId) throws IOException {
        Path path = taskPath(taskId);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(path.toFile(), AgentTask.class));
    }

    @Override
    public synchronized List<AgentTask> findAll() throws IOException {
        if (!Files.isDirectory(storeDirectory)) {
            return List.of();
        }
        List<AgentTask> tasks = new ArrayList<>();
        try (var paths = Files.list(storeDirectory)) {
            for (Path path : paths.filter(item -> item.getFileName().toString().endsWith(".json")).toList()) {
                tasks.add(objectMapper.readValue(path.toFile(), AgentTask.class));
            }
        }
        tasks.sort(Comparator.comparing(AgentTask::createdAt).reversed());
        return tasks;
    }

    private Path taskPath(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9-]+")) {
            throw new IllegalArgumentException("非法任务 ID");
        }
        return storeDirectory.resolve(taskId + ".json");
    }

    private ObjectMapper configureTimeModule(ObjectMapper mapper) {
        SimpleModule module = new SimpleModule("agent-task-instant");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializerProvider serializers)
                    throws IOException {
                generator.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return Instant.parse(parser.getValueAsString());
            }
        });
        return mapper.registerModule(module);
    }
}
