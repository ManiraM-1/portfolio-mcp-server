package com.maniram.portfolio_mcp_server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public class DataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("Checking whether portfolio data needs (re)loading");

        // Read the JSON once, as raw bytes — used both for hashing and for parsing.
        byte[] jsonBytes;
        try (InputStream is = new ClassPathResource("portfolio-data.json").getInputStream()) {
            jsonBytes = is.readAllBytes();
        }
        String contentHash = sha256Hex(jsonBytes);

        // A row-count-only guard (">0 rows means loaded") can't detect that the
        // *content* of portfolio-data.json changed — it only detects "empty vs
        // not empty". Tracking a hash of the source file instead means: same
        // content -> skip (cheap, no re-embedding); any change at all (added,
        // removed, or edited entries) -> reload, so the vector store never goes
        // stale relative to the JSON.
        ensureVersionTableExists();
        String previousHash = getStoredHash();

        if (contentHash.equals(previousHash)) {
            log.info("Portfolio data unchanged (hash {}...), skipping ingestion.",
                contentHash.substring(0, 8));
            return;
        }

        log.info("Portfolio data changed (or first run) — reloading vector store.");

        // Clear whatever's currently stored before inserting the fresh set, so
        // edited/removed entries don't linger alongside their replacements.
        jdbcTemplate.update("DELETE FROM vector_store");

        @SuppressWarnings("unchecked")
        Map<String, Object> data = objectMapper.readValue(jsonBytes, Map.class);
        List<Document> documents = buildDocuments(data);

        vectorStore.add(documents);
        saveHash(contentHash);

        log.info("Successfully loaded {} documents into vector store.", documents.size());
    }

    @SuppressWarnings("unchecked")
    private List<Document> buildDocuments(Map<String, Object> data) {
        List<Document> documents = new ArrayList<>();

        // Convert experience to documents
        List<Map<String, Object>> experience =
            (List<Map<String, Object>>) data.get("experience");
        for (Map<String, Object> exp : experience) {
            String content = String.format(
                "Role: %s at %s (%s)\nPeriod: %s\nWork done:\n- %s",
                exp.get("role"),
                exp.get("company"),
                exp.get("location"),
                exp.get("period"),
                String.join("\n- ", (List<String>) exp.get("bullets"))
            );
            documents.add(new Document(content,
                Map.of("source", "experience", "company", exp.get("company"))));
        }

        // Convert projects to documents
        List<Map<String, Object>> projects =
            (List<Map<String, Object>>) data.get("projects");
        for (Map<String, Object> project : projects) {
            String content = String.format(
                "Project: %s\nDescription: %s\nKey metric: %s\n" +
                "Technologies: %s\nDetails:\n- %s",
                project.get("name"),
                project.get("subtitle"),
                project.get("metric"),
                String.join(", ", (List<String>) project.get("tech")),
                String.join("\n- ", (List<String>) project.get("bullets"))
            );
            documents.add(new Document(content,
                Map.of("source", "projects", "name", project.get("name"))));
        }

        // Convert skills to one document
        Map<String, List<String>> skills =
            (Map<String, List<String>>) data.get("skills");
        StringBuilder skillsContent = new StringBuilder("Technical Skills:\n");
        skills.forEach((category, items) ->
            skillsContent.append(category)
                         .append(": ")
                         .append(String.join(", ", items))
                         .append("\n")
        );
        documents.add(new Document(skillsContent.toString(),
            Map.of("source", "skills")));

        // Convert contact + education to one document
        Map<String, Object> contact = (Map<String, Object>) data.get("contact");
        Map<String, Object> education = (Map<String, Object>) contact.get("education");
        String contactContent = String.format(
            "Contact Information:\nName: %s\nEmail: %s\n" +
            "LinkedIn: %s\nGitHub: %s\nLocation: %s\n" +
            "Education: %s at %s (%s), CGPA: %s",
            contact.get("name"), contact.get("email"),
            contact.get("linkedin"), contact.get("github"),
            contact.get("location"),
            education.get("degree"), education.get("institution"),
            education.get("period"), education.get("cgpa")
        );
        documents.add(new Document(contactContent,
            Map.of("source", "contact")));

        return documents;
    }

    private void ensureVersionTableExists() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS data_loader_state (
                id INT PRIMARY KEY,
                content_hash VARCHAR(64) NOT NULL
            )
            """);
    }

    private String getStoredHash() {
        return jdbcTemplate.query(
            "SELECT content_hash FROM data_loader_state WHERE id = 1",
            rs -> rs.next() ? rs.getString(1) : null
        );
    }

    private void saveHash(String hash) {
        jdbcTemplate.update("""
            INSERT INTO data_loader_state (id, content_hash) VALUES (1, ?)
            ON CONFLICT (id) DO UPDATE SET content_hash = EXCLUDED.content_hash
            """, hash);
    }

    private static String sha256Hex(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        return HexFormat.of().formatHex(digest);
    }
}
