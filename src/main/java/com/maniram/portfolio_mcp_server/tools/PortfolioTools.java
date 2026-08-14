package com.maniram.portfolio_mcp_server.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PortfolioTools {

    private static final Logger log = LoggerFactory.getLogger(PortfolioTools.class);

    @Autowired
    private VectorStore vectorStore;

    @Tool(description = """
            Search through Maniram Tatipamula's portfolio documents.
            Use this for any question about his experience, projects,
            skills, education, achievements, or background.
            Input should be a natural language search query.
            """)
    public String searchPortfolio(String query) {
        log.info("Searching portfolio for: {}", query);

        // Search PGVector for top 3 most relevant chunks
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.5)
                .build()
        );

        if (results.isEmpty()) {
            return "No relevant information found for: " + query;
        }

        // Join the top results into one string for Gemini to use
        return results.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = """
            Returns Maniram's contact information including email,
            LinkedIn, GitHub, and location. Use this when someone
            asks how to contact him or reach out.
            """)
    public String getContact() {
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.builder()
                .query("contact email linkedin github location")
                .topK(1)
                .build()
        );
        return results.isEmpty() ? "Contact info not found" 
            : results.get(0).getText();
    }
}