package com.lightnote.ai.provider.springai;

import com.lightnote.ai.rag.AiRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SpringAiRagAdvisor implements BaseAdvisor {

    public static final String RAG_CONTEXT_KEY = "lightnote.rag.context";

    private final AiRagService aiRagService;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userText = extractUserText(request);
        if (!StringUtils.hasText(userText)) {
            return request;
        }

        String ragContext = aiRagService.buildContext(userText);
        if (!StringUtils.hasText(ragContext)) {
            return request;
        }

        Prompt prompt = request.prompt().augmentSystemMessage(ragContext);
        return request.mutate()
                .prompt(prompt)
                .context(RAG_CONTEXT_KEY, ragContext)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public String getName() {
        return "LightNoteSpringAiRagAdvisor";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    private String extractUserText(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return "";
        }
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage == null ? "" : userMessage.getText();
    }
}
