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
import vn.udn.dut.it2.model.dto.DocDetailDto;
import vn.udn.dut.it2.model.entity.AcademicEvent;
import vn.udn.dut.it2.model.entity.ProcedureCriteria;
import vn.udn.dut.it2.model.entity.ProcedureDoc;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.model.entity.Student;
import vn.udn.dut.it2.repository.AcademicEventRepository;
import vn.udn.dut.it2.repository.ProcedureCriteriaRepository;
import vn.udn.dut.it2.repository.ProcedureDocRepository;
import vn.udn.dut.it2.repository.RegulationRepository;
import vn.udn.dut.it2.repository.StudentRepository;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private static final List<String> DEFAULT_SUGGESTIONS = Arrays.asList(
            "Điều kiện xét học bổng khuyến khích học tập loại Giỏi là bao nhiêu?",
            "Sinh viên bị cảnh báo học tập hoặc buộc thôi học khi nào?",
            "Thủ tục xin cấp Giấy xác nhận sinh viên thực hiện như thế nào?",
            "Hạn chót rút bớt học phần kỳ này là khi nào?"
    );

    private static final DateTimeFormatter EVENT_DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AiProperties aiProperties;
    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;
    private final RegulationRepository regulationRepository;
    private final ProcedureDocRepository procedureDocRepository;
    private final ProcedureCriteriaRepository procedureCriteriaRepository;
    private final AcademicEventRepository academicEventRepository;
    private final StudentRepository studentRepository;

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

        String legalContext = retrieval.path("context").asText("");
        String portalContext = buildPortalContext(request.getMessage(), request.getStudentCode());

        if ((retrieval.path("total_found").asInt(0) == 0 || legalContext.isBlank()) && portalContext.isBlank()) {
            log.info("[AI-CHAT] No relevant chunks in ChromaDB or Portal DB; skipping Gemini call");
            return ChatDto.Response.builder()
                    .answer("Hiện tại hệ thống chưa tìm thấy điều khoản phù hợp trong kho văn bản pháp quy hoặc dữ liệu Cổng Sinh viên DUT cho câu hỏi này. "
                            + "Vui lòng diễn đạt cụ thể hơn hoặc liên hệ Phòng Đào tạo / Phòng Công tác Sinh viên để được hỗ trợ.")
                    .sources(List.of())
                    .suggestions(DEFAULT_SUGGESTIONS)
                    .build();
        }

        String combinedContext = combineContext(legalContext, portalContext);
        return generateAnswer(request, retrieval, combinedContext, apiKey);
    }

    private String combineContext(String legalContext, String portalContext) {
        if (portalContext == null || portalContext.isBlank()) {
            return legalContext;
        }
        if (legalContext == null || legalContext.isBlank()) {
            return portalContext;
        }
        return portalContext + "\n\n=== VĂN BẢN PHÁP QUY TRÍCH XUẤT ===\n" + legalContext;
    }

    private String buildPortalContext(String query, String studentCode) {
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();

        if (studentCode != null && !studentCode.isBlank()) {
            studentRepository.findByStudentCode(studentCode.trim()).ifPresent((Student st) ->
                    sb.append("[HỒ SƠ SINH VIÊN ĐANG ĐĂNG NHẬP] ")
                            .append(st.getFullName())
                            .append(" (MSSV: ").append(st.getStudentCode())
                            .append(", Lớp: ").append(st.getClassName())
                            .append(", ").append(st.getFaculty())
                            .append(") | ĐTBCHK (thang 10): ").append(st.getGpa())
                            .append(" | ĐRL: ").append(st.getTrainingPoint())
                            .append(" | Tín chỉ: ").append(st.getAccumulatedCredits())
                            .append("/").append(st.getTotalCredits())
                            .append("\n"));
        }

        List<AcademicEvent> events = academicEventRepository.findAll();
        if (events != null && !events.isEmpty()) {
            sb.append("\n[LỊCH & MỐC HẠN CHÓT HỌC VỤ CỔNG SINH VIÊN DUT]\n");
            for (AcademicEvent ev : events) {
                String dateStr = ev.getEventDate() != null ? ev.getEventDate().format(EVENT_DATE_FMT) : "";
                sb.append("- ").append(Boolean.TRUE.equals(ev.getIsDeadline()) ? "[HẠN CHÓT] " : "[SỰ KIỆN] ")
                        .append(ev.getTitle())
                        .append(" (Ngày: ").append(dateStr)
                        .append(", Phạm vi: ").append(ev.getClassScope())
                        .append("): ").append(ev.getDescription() == null ? "" : ev.getDescription())
                        .append("\n");
            }
        }

        List<ProcedureDoc> procedures = procedureDocRepository.findAllByOrderByIdAsc();
        if (procedures != null && !procedures.isEmpty()) {
            boolean wantsProcedure = containsAny(lower,
                    "thủ tục", "thu tuc", "biểu mẫu", "bieu mau", "một cửa", "mot cua",
                    "trực tuyến", "truc tuyen", "xác nhận", "xac nhan", "phúc khảo", "phuc khao",
                    "bảo lưu", "bao luu", "tạm dừng", "ký túc xá", "ktx", "học bổng", "hoc bong", "nộp đơn");
            if (wantsProcedure) {
                sb.append("\n[THỦ TỤC HÀNH CHÍNH & BIỂU MẪU MỘT CỬA DUT]\n");
                for (ProcedureDoc doc : procedures) {
                    sb.append("* Thủ tục #").append(doc.getId()).append(": ").append(doc.getTitle())
                            .append(" — ").append(doc.getSummary() == null ? "" : doc.getSummary())
                            .append(" (Tải mẫu: ").append(doc.getFileUrl()).append(")\n");
                    if (doc.getSteps() != null) {
                        for (DocDetailDto.Step step : doc.getSteps()) {
                            sb.append("  + Bước ").append(step.getOrder()).append(" (").append(step.getTitle())
                                    .append("): ").append(step.getDescription()).append("\n");
                        }
                    }
                }
            }
        }

        if (containsAny(lower, "học bổng", "hoc bong", "gpa", "rèn luyện", "ren luyen", "1414", "xuất sắc", "loại giỏi", "loai gioi", "tiền", "tín chỉ")) {
            List<ProcedureCriteria> criteriaList = procedureCriteriaRepository.findByProcedureName("Học bổng Khuyến khích Học tập");
            if (criteriaList != null && !criteriaList.isEmpty()) {
                sb.append("\n[BẢNG TIÊU CHUẨN & MỨC HỌC BỔNG KKHT THEO QĐ 1414/QĐ-ĐHBK (THANG ĐIỂM 10)]\n");
                for (ProcedureCriteria c : criteriaList) {
                    sb.append("- ").append(c.getGradeLevel())
                            .append(": ĐTBCHK >= ").append(c.getMinGpa())
                            .append(c.getMaxGpa() != null ? " đến " + c.getMaxGpa() : "")
                            .append(", ĐRL >= ").append(c.getMinTrainingPoint())
                            .append(" (").append(c.getDescription()).append(")\n");
                }
                sb.append("- Mức học bổng (Điều 6 QĐ 1414/QĐ-ĐHBK): Loại Khá (C) = 100% mức học phí hiện hành của CTĐT đối với các học phần học lần đầu; Loại Giỏi (B) = 120% mức loại Khá; Loại Xuất sắc (A) = 150% mức loại Khá.\n");
                sb.append("- Điều kiện kèm theo (Điều 5 QĐ 1414/QĐ-ĐHBK): tổng số tín chỉ học phần lần đầu trong kỳ >= 15 tín chỉ (trừ kỳ thiết kế < 15), không có học phần nào dưới điểm 5,0 (thang 10), không chịu kỷ luật từ khiển trách trở lên.\n");
            }
        }

        return sb.toString().trim();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
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

    private ChatDto.Response generateAnswer(ChatDto.Request request, JsonNode retrieval, String context, String apiKey) {
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
        long budgetStartMs = System.currentTimeMillis();
        final long maxBudgetMs = 28_000L;
        for (int idx = 0; idx < candidateModels.size(); idx++) {
            if (idx > 0 && System.currentTimeMillis() - budgetStartMs > maxBudgetMs) {
                log.warn("[AI-CHAT] Retry time budget exceeded ({} ms); returning fallback",
                        System.currentTimeMillis() - budgetStartMs);
                break;
            }
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
            response.getSources().removeIf(s -> s == null
                    || s.getDocumentNumber() == null
                    || s.getDocumentNumber().isBlank()
                    || s.getDocumentNumber().trim().equalsIgnoreCase("N/A")
                    || s.getDocumentNumber().trim().equalsIgnoreCase("none")
                    || s.getDocumentNumber().trim().equalsIgnoreCase("không có"));
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
