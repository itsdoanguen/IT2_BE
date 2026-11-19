package vn.udn.dut.it2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import vn.udn.dut.it2.config.AiProperties;
import vn.udn.dut.it2.model.dto.ChatDto;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.RegulationRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final List<String> DEFAULT_SUGGESTIONS = Arrays.asList(
            "Tiêu chuẩn học bổng loại Giỏi là bao nhiêu?",
            "Điều kiện số tín chỉ tối thiểu để xét học bổng?",
            "Thủ tục xin cấp giấy xác nhận sinh viên trực tuyến?",
            "Hạn chót rút bớt học phần kỳ này là khi nào?"
    );

    private final AiProperties aiProperties;
    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;
    private final RegulationRepository regulationRepository;

    public ChatDto.Response processChat(ChatDto.Request request) {
        String provider = aiProperties.getProvider() == null ? "mock" : aiProperties.getProvider().trim();
        log.info("[AI-CHAT] Incoming query (provider={}, model={}, studentCode={}): {}",
                provider, aiProperties.getModel(), request.getStudentCode(), request.getMessage());

        if ("mock".equalsIgnoreCase(provider)) {
            log.info("[AI-CHAT] Running in mock mode");
            return mockResponse(request.getMessage());
        }

        if (!"gemini".equalsIgnoreCase(provider)) {
            log.error("[AI-CHAT] Unsupported AI provider: {}", provider);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Unsupported AI provider: " + provider);
        }

        String apiKey = cleanApiKey(aiProperties.getApiKey());
        if (apiKey.isBlank()) {
            log.error("[AI-CHAT] AI_API_KEY is missing or blank");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_API_KEY is not configured");
        }

        JsonNode retrieval = retrieveContext(request.getMessage());
        if (retrieval.path("blocked").asBoolean(false)) {
            String refusal = retrieval.path("refusal_message").asText(
                    "Xin lỗi, câu hỏi nằm ngoài phạm vi tra cứu quy chế và thủ tục học vụ của Trường ĐHBK – ĐHĐN.");
            log.info("[AI-CHAT] Blocked by guardrail (category={})",
                    retrieval.path("guardrail_category").asText("unknown"));
            return ChatDto.Response.builder()
                    .answer(refusal)
                    .sources(List.of())
                    .suggestions(DEFAULT_SUGGESTIONS)
                    .build();
        }

        String context = retrieval.path("context").asText("");
        if (retrieval.path("total_found").asInt(0) == 0 || context.isBlank()) {
            log.info("[AI-CHAT] No relevant chunks found in ChromaDB; skipping Gemini call");
            return ChatDto.Response.builder()
                    .answer("Hiện tại hệ thống chưa tìm thấy điều khoản phù hợp trong kho văn bản pháp quy DUT cho câu hỏi này. "
                            + "Vui lòng diễn đạt cụ thể hơn hoặc liên hệ Phòng Đào tạo / Phòng Công tác Sinh viên để được hỗ trợ.")
                    .sources(List.of())
                    .suggestions(DEFAULT_SUGGESTIONS)
                    .build();
        }

        return generateAnswer(request, retrieval, apiKey);
    }

    private JsonNode retrieveContext(String message) {
        String url = aiProperties.getRetrievalBaseUrl() + aiProperties.getRetrievalPath();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", message);
        payload.put("topK", aiProperties.getRetrievalTopK());
        payload.put("top_k", aiProperties.getRetrievalTopK());
        payload.put("check_guardrail", true);

        long start = System.currentTimeMillis();
        log.info("[AI-CHAT] Calling retrieval service: {} (topK={})", url, aiProperties.getRetrievalTopK());
        try {
            JsonNode response = aiRestClient.post()
                    .uri(url)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                log.error("[AI-CHAT] Retrieval service returned null/empty body from {}", url);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Retrieval service returned an empty response");
            }
            log.info("[AI-CHAT] Retrieval completed in {} ms (total_found={}, blocked={}, contextChars={})",
                    System.currentTimeMillis() - start,
                    response.path("total_found").asInt(0),
                    response.path("blocked").asBoolean(false),
                    response.path("context").asText("").length());
            return response;
        } catch (RestClientResponseException exception) {
            log.error("[AI-CHAT] Retrieval HTTP error {} from {}: {}",
                    exception.getStatusCode(), url, exception.getResponseBodyAsString(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "AI search service error: " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            log.error("[AI-CHAT] Could not reach retrieval service at {}: {}", url, exception.getMessage(), exception);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Could not retrieve context from the AI search service", exception);
        }
    }

    private ChatDto.Response generateAnswer(ChatDto.Request request, JsonNode retrieval, String apiKey) {
        String context = retrieval.has("context")
                ? retrieval.get("context").asText()
                : retrieval.toString();
        String systemPrompt = aiProperties.getSystemPrompt();
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = "Answer only from the supplied context and return valid JSON.";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", "CONTEXT:\n" + context + "\n\nQUESTION:\n" + request.getMessage()))
        )));
        body.put("generationConfig", Map.of(
                "temperature", 0.1,
                "maxOutputTokens", aiProperties.getMaxOutputTokens(),
                "responseMimeType", "application/json",
                "responseSchema", responseSchema()
        ));

        String primaryModel = (aiProperties.getModel() == null || aiProperties.getModel().isBlank())
                ? "gemini-3.1-flash-lite-preview"
                : aiProperties.getModel().trim();
        List<String> candidateModels = new ArrayList<>();
        candidateModels.add(primaryModel);
        for (String fallback : List.of("gemini-3.1-flash-lite-preview", "gemini-3.1-flash-lite", "gemini-flash-lite-latest")) {
            if (candidateModels.stream().noneMatch(m -> m.equalsIgnoreCase(fallback))) {
                candidateModels.add(fallback);
            }
        }

        ResponseStatusException lastError = null;
        boolean transientFailure = false;
        for (int idx = 0; idx < candidateModels.size(); idx++) {
            String model = candidateModels.get(idx);
            String endpointNoKey = aiProperties.getGeminiBaseUrl() + "/v1beta/models/" + model + ":generateContent";
            long start = System.currentTimeMillis();
            log.info("[AI-CHAT] Calling Gemini endpoint={} (contextChars={}, apiKeyLen={})",
                    endpointNoKey, context.length(), apiKey.length());

            try {
                JsonNode response = aiRestClient.post()
                        .uri(endpointNoKey + "?key=" + apiKey)
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                String json = response == null
                        ? ""
                        : response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
                if (json.isBlank()) {
                    log.error("[AI-CHAT] Gemini returned empty text candidate (model={}, elapsed={} ms). Raw response: {}",
                            model, System.currentTimeMillis() - start, response);
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned no answer");
                }
                ChatDto.Response parsed = parseResponse(json);
                log.info("[AI-CHAT] Gemini succeeded in {} ms (model={}, sources={}, suggestions={})",
                        System.currentTimeMillis() - start,
                        model,
                        parsed.getSources().size(),
                        parsed.getSuggestions().size());
                return parsed;
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                log.error("[AI-CHAT] Gemini API HTTP error {} (model={}, {} ms): {}",
                        exception.getStatusCode(),
                        model,
                        System.currentTimeMillis() - start,
                        exception.getResponseBodyAsString());
                lastError = new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Gemini API error: " + exception.getStatusCode(), exception);
                if (status == 404 || status == 503 || status == 429) {
                    transientFailure = (status == 503 || status == 429);
                    if (idx + 1 < candidateModels.size()) {
                        log.warn("[AI-CHAT] Retrying with fallback model {}", candidateModels.get(idx + 1));
                        continue;
                    }
                }
                if (transientFailure) {
                    break;
                }
                throw lastError;
            } catch (RestClientException exception) {
                log.error("[AI-CHAT] Gemini connection/timeout failed (model={}, {} ms): {}",
                        model, System.currentTimeMillis() - start, exception.getMessage(), exception);
                transientFailure = true;
                lastError = new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini request failed", exception);
                if (idx + 1 < candidateModels.size()) {
                    log.warn("[AI-CHAT] Retrying with fallback model {}", candidateModels.get(idx + 1));
                    continue;
                }
                break;
            } catch (ResponseStatusException exception) {
                throw exception;
            } catch (Exception exception) {
                log.error("[AI-CHAT] Failed to parse Gemini JSON response (model={}, {} ms): {}",
                        model, System.currentTimeMillis() - start, exception.getMessage(), exception);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini returned invalid JSON", exception);
            }
        }

        if (transientFailure && retrieval.path("results").isArray() && !retrieval.path("results").isEmpty()) {
            return buildRetrievalFallback(retrieval);
        }
        throw lastError != null ? lastError : new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Gemini request failed");
    }

    private ChatDto.Response buildRetrievalFallback(JsonNode retrieval) {
        List<ChatDto.Citation> sources = new ArrayList<>();
        StringBuilder answer = new StringBuilder(
                "Dịch vụ tổng hợp Gemini hiện đang quá tải tạm thời; dưới đây là các điều khoản liên quan nhất "
                        + "được trích xuất trực tiếp từ kho văn bản pháp quy DUT:\n\n");

        int count = 0;
        for (JsonNode item : retrieval.path("results")) {
            if (count >= 3) {
                break;
            }
            JsonNode meta = item.path("metadata");
            String docNum = meta.path("document_number").asText("").trim();
            String docTitle = meta.path("document_title").asText("").trim();
            String article = meta.path("article_number").asText("").trim();
            String clause = meta.path("clause_number").asText("").trim();
            String clauseLabel = clause.isEmpty() || "all".equalsIgnoreCase(clause)
                    ? article
                    : (article.isEmpty() ? "Khoản " + clause : article + ", Khoản " + clause);
            String excerpt = item.path("content").asText("").trim();

            if (excerpt.isEmpty()) {
                continue;
            }
            count++;
            String preview = excerpt.length() > 320 ? excerpt.substring(0, 320) + "..." : excerpt;
            answer.append(count).append(". **").append(docNum.isEmpty() ? "Văn bản DUT" : docNum);
            if (!clauseLabel.isEmpty()) {
                answer.append(" (").append(clauseLabel).append(")");
            }
            answer.append(":** ").append(preview).append("\n\n");

            sources.add(ChatDto.Citation.builder()
                    .documentNumber(docNum)
                    .documentTitle(docTitle)
                    .clause(clauseLabel)
                    .excerpt(preview)
                    .pdfUrl("")
                    .build());
        }

        enrichSourceUrls(sources);
        log.warn("[AI-CHAT] Returning extractive retrieval fallback with {} sources due to transient Gemini failure",
                sources.size());
        return ChatDto.Response.builder()
                .answer(answer.toString().trim())
                .sources(sources)
                .suggestions(DEFAULT_SUGGESTIONS)
                .build();
    }

    private static String cleanApiKey(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private ChatDto.Response parseResponse(String json) throws Exception {
        String normalized = json.trim();
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        ChatDto.Response response = objectMapper.readValue(normalized, ChatDto.Response.class);
        if (response.getAnswer() == null || response.getAnswer().isBlank()) {
            throw new IllegalArgumentException("AI response is missing answer");
        }
        if (response.getSources() == null) {
            response.setSources(new ArrayList<>());
        } else {
            enrichSourceUrls(response.getSources());
        }
        if (response.getSuggestions() == null) {
            response.setSuggestions(DEFAULT_SUGGESTIONS);
        }
        return response;
    }

    private void enrichSourceUrls(List<ChatDto.Citation> sources) {
        for (ChatDto.Citation source : sources) {
            if (source == null || source.getDocumentNumber() == null || source.getDocumentNumber().isBlank()) {
                continue;
            }
            String existingUrl = source.getPdfUrl();
            boolean needsEnrich = existingUrl == null
                    || existingUrl.isBlank()
                    || existingUrl.contains("example.com")
                    || (!existingUrl.startsWith("http") && !existingUrl.startsWith("/"));
            if (needsEnrich) {
                regulationRepository.findFirstByDocumentNumberIgnoreCase(source.getDocumentNumber().trim())
                        .map(Regulation::getPdfUrl)
                        .filter(url -> url != null && !url.isBlank())
                        .ifPresent(source::setPdfUrl);
            }
        }
    }

    private Map<String, Object> responseSchema() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "OBJECT");
        source.put("properties", Map.of(
                "documentNumber", Map.of("type", "STRING"),
                "documentTitle", Map.of("type", "STRING"),
                "clause", Map.of("type", "STRING"),
                "excerpt", Map.of("type", "STRING"),
                "pageNumber", Map.of("type", "INTEGER"),
                "pdfUrl", Map.of("type", "STRING")
        ));
        source.put("required", List.of("documentNumber", "documentTitle", "clause", "excerpt", "pdfUrl"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", Map.of(
                "answer", Map.of("type", "STRING"),
                "sources", Map.of("type", "ARRAY", "items", source),
                "suggestions", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))
        ));
        schema.put("required", List.of("answer", "sources"));
        return schema;
    }

    private ChatDto.Response mockResponse(String message) {
        String lowerMessage = message == null ? "" : message.toLowerCase();
        List<ChatDto.Citation> sources = new ArrayList<>();
        String answer = "Trợ lý học vụ DUT đang chạy ở chế độ mock. Hãy cấu hình AI_PROVIDER=gemini "
                + "và AI_API_KEY để bật truy xuất văn bản và Gemini.";

        if (lowerMessage.contains("học bổng") || lowerMessage.contains("giỏi")) {
            answer = "Theo dữ liệu mẫu, tiêu chuẩn học bổng loại Giỏi là điểm học tập từ 8.00 "
                    + "đến 8.99 và điểm rèn luyện từ 80 điểm trở lên.";
            sources.add(ChatDto.Citation.builder()
                    .documentNumber("1414/QĐ-ĐHBK")
                    .documentTitle("Quy định xét cấp HBKKHT tại Trường ĐHBK-ĐHĐN")
                    .clause("Điều 4, Khoản 2")
                    .excerpt("Mức học bổng loại Giỏi căn cứ vào kết quả học tập và rèn luyện.")
                    .pdfUrl("/tai-lieu-riel/QD_1414_DHBK.pdf")
                    .build());
        }

        return ChatDto.Response.builder()
                .answer(answer)
                .sources(sources)
                .suggestions(DEFAULT_SUGGESTIONS)
                .build();
    }
}
