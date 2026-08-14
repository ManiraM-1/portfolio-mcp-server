package com.maniram.portfolio_mcp_server.controller;

import com.maniram.portfolio_mcp_server.tools.PortfolioTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatClient chatClient;

    @Autowired
    public ChatController(GoogleGenAiChatModel chatModel, 
                          PortfolioTools portfolioTools) {
        // Build a ChatClient with your tools registered
        this.chatClient = ChatClient.builder(chatModel)
            .defaultSystem("""
                You are an AI assistant for Maniram Tatipamula's portfolio website.
                Your job is to answer questions from recruiters and visitors about Maniram's
                background, experience, projects, and skills.

                Rules:
                - Always call searchPortfolio first before answering any question about his work
                - Be concise — 3-5 sentences maximum unless asked for detail
                - Be factual — only state what the documents say, never invent details
                - Be professional — this is a portfolio, not a casual conversation
                - If someone asks how to contact Maniram, call getContact()
                - If you cannot find relevant information, say: "I don't have specific information
                  about that. You can reach Maniram directly at maniram24crt@gmail.com"
                """)
            .defaultTools(portfolioTools)
            .build();
    }

    // The request body shape: { "question": "..." }
    public record ChatRequest(String question) {}

    // The response body shape: { "answer": "..." }
    public record ChatResponse(String answer) {}

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("Received question: {}", request.question());

        if (request.question() == null || request.question().isBlank()) {
            return ResponseEntity.badRequest()
                .body(new ChatResponse("Please ask a question."));
        }

        try {
            // Send question to Gemini — it decides which tools to call
            String answer = chatClient.prompt()
                .user(request.question())
                .call()
                .content();

            log.info("Answer generated successfully");
            return ResponseEntity.ok(new ChatResponse(answer));

        } catch (Exception e) {
            log.error("Error generating answer", e);
            return ResponseEntity.internalServerError()
                .body(new ChatResponse("Sorry, something went wrong. Try again."));
        }
    }

    // Health check endpoint — used to wake the server on Hugging Face
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "server", "portfolio-mcp-server"
        ));
 
    }
}