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

    /**
     * 在 Spring AI 发起模型调用前，根据当前用户输入检索 RAG 上下文并追加到系统提示中。
     *
     * @param request 当前 ChatClient 请求
     * @param chain Advisor 调用链
     * @return 增强后的请求；无用户输入或无 RAG 命中时返回原请求
     */
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

    /**
     * 模型响应后不做额外处理，保持响应内容原样返回。
     *
     * @param response 当前 ChatClient 响应
     * @param chain Advisor 调用链
     * @return 原始响应
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * 返回 Advisor 名称，便于 Spring AI 调试和链路识别。
     *
     * @return 当前 RAG Advisor 的名称
     */
    @Override
    public String getName() {
        return "LightNoteSpringAiRagAdvisor";
    }

    /**
     * 控制 RAG Advisor 在 Advisor 链中的执行顺序。
     *
     * @return Advisor 排序值
     */
    @Override
    public int getOrder() {
        return 20;
    }

    /**
     * 从 ChatClient 请求中提取当前用户消息文本。
     *
     * @param request 当前 ChatClient 请求
     * @return 用户输入文本；请求为空时返回空字符串
     */
    private String extractUserText(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return "";
        }
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage == null ? "" : userMessage.getText();
    }
}
