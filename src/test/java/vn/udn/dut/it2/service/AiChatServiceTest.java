package vn.udn.dut.it2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.udn.dut.it2.config.AiProperties;
import vn.udn.dut.it2.model.dto.ChatDto;
import vn.udn.dut.it2.model.entity.AcademicEvent;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.AcademicEventRepository;
import vn.udn.dut.it2.repository.ProcedureCriteriaRepository;
import vn.udn.dut.it2.repository.ProcedureDocRepository;
import vn.udn.dut.it2.repository.RegulationRepository;
import vn.udn.dut.it2.repository.StudentRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    @Mock
    private RegulationRepository regulationRepository;
    @Mock
    private ProcedureDocRepository procedureDocRepository;
    @Mock
    private ProcedureCriteriaRepository procedureCriteriaRepository;
    @Mock
    private AcademicEventRepository academicEventRepository;
    @Mock
    private StudentRepository studentRepository;

    private MockRestServiceServer mockServer;
    private AiProperties aiProperties;
    private AiChatService aiChatService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        aiProperties = new AiProperties();
        aiProperties.setProvider("gemini");
        aiProperties.setApiKey("test-key");
        aiProperties.setModel("gemini-2.0-flash");
        aiProperties.setGeminiBaseUrl("https://generativelanguage.googleapis.com");
        aiProperties.setRetrievalBaseUrl("http://ai:8000");
        aiProperties.setRetrievalPath("/api/search");

        aiChatService = new AiChatService(
                aiProperties,
                builder.build(),
                new ObjectMapper(),
                regulationRepository,
                procedureDocRepository,
                procedureCriteriaRepository,
                academicEventRepository,
                studentRepository);
    }

    @Test
    void processChat_shortCircuitsWhenGuardrailBlocks() {
        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "Viết code Python",
                          "top_k": 5,
                          "total_found": 0,
                          "blocked": true,
                          "guardrail_category": "coding",
                          "refusal_message": "Tôi chỉ hỗ trợ tra cứu quy chế DUT.",
                          "results": []
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatDto.Request req = new ChatDto.Request("Viết code Python", "102230001");
        ChatDto.Response res = aiChatService.processChat(req);

        mockServer.verify();
        assertThat(res.getAnswer()).isEqualTo("Tôi chỉ hỗ trợ tra cứu quy chế DUT.");
        assertThat(res.getSources()).isEmpty();
    }

    @Test
    void processChat_retrievesContextCallsGeminiAndEnrichesPdfUrl() {
        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "tiêu chuẩn học bổng loại giỏi",
                          "top_k": 5,
                          "total_found": 1,
                          "blocked": false,
                          "context": "[VĂN BẢN: 1414/QĐ-ĐHBK] Điều 4: ĐTBCHK từ 8.00 đến 8.99.",
                          "results": []
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=test-key"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  {
                                    "text": "{\\"answer\\":\\"Theo Điều 4 QĐ 1414/QĐ-ĐHBK, loại Giỏi yêu cầu ĐTBCHK 8.00 - 8.99.\\",\\"sources\\":[{\\"documentNumber\\":\\"1414/QĐ-ĐHBK\\",\\"documentTitle\\":\\"Quy định HBKKHT\\",\\"clause\\":\\"Điều 4\\",\\"excerpt\\":\\"ĐTBCHK từ 8.00 đến 8.99\\",\\"pdfUrl\\":\\"\\"}],\\"suggestions\\":[\\"Điều kiện tín chỉ tối thiểu?\\"]}"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        when(regulationRepository.findFirstByDocumentNumberIgnoreCase("1414/QĐ-ĐHBK"))
                .thenReturn(Optional.of(Regulation.builder()
                        .documentNumber("1414/QĐ-ĐHBK")
                        .pdfUrl("http://dut.udn.vn/files/1414.pdf")
                        .build()));

        ChatDto.Response res = aiChatService.processChat(new ChatDto.Request("tiêu chuẩn học bổng loại giỏi", "102230001"));

        mockServer.verify();
        assertThat(res.getAnswer()).contains("1414/QĐ-ĐHBK");
        assertThat(res.getSources()).hasSize(1);
        assertThat(res.getSources().get(0).getPdfUrl()).isEqualTo("http://dut.udn.vn/files/1414.pdf");
    }

    @Test
    void processChat_returnsExtractiveFallbackWhenAllGeminiModelsReturn503() {
        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "cảnh cáo học vụ",
                          "top_k": 5,
                          "total_found": 1,
                          "blocked": false,
                          "context": "[VĂN BẢN: 2244/QĐ-ĐHBK] Điều 12: Cảnh báo học tập.",
                          "results": [
                            {
                              "content": "Sinh viên không tích lũy đủ tín chỉ tối thiểu bị cảnh báo học tập.",
                              "metadata": {
                                "document_number": "2244/QĐ-ĐHBK",
                                "document_title": "Quy chế đào tạo",
                                "article_number": "Điều 12",
                                "clause_number": "1"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=test-key"))
                .andRespond(withServiceUnavailable());
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent?key=test-key"))
                .andRespond(withServiceUnavailable());
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent?key=test-key"))
                .andRespond(withServiceUnavailable());
        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-lite-latest:generateContent?key=test-key"))
                .andRespond(withServiceUnavailable());

        when(regulationRepository.findFirstByDocumentNumberIgnoreCase("2244/QĐ-ĐHBK"))
                .thenReturn(Optional.of(Regulation.builder()
                        .documentNumber("2244/QĐ-ĐHBK")
                        .pdfUrl("http://dut.udn.vn/files/2244.pdf")
                        .build()));

        ChatDto.Response res = aiChatService.processChat(new ChatDto.Request("cảnh cáo học vụ", "102230001"));

        mockServer.verify();
        assertThat(res.getAnswer()).contains("2244/QĐ-ĐHBK");
        assertThat(res.getSources()).hasSize(1);
        assertThat(res.getSources().get(0).getPdfUrl()).isEqualTo("http://dut.udn.vn/files/2244.pdf");
    }

    @Test
    void processChat_usesPortalEventsAndProceduresEvenWhenVectorReturnsZero() {
        when(academicEventRepository.findAll()).thenReturn(List.of(
                AcademicEvent.builder()
                        .title("Hạn rút bớt học phần tín chỉ đợt 2")
                        .eventDate(LocalDateTime.of(2026, 12, 1, 17, 0))
                        .isDeadline(true)
                        .classScope("ALL")
                        .description("Hạn cuối nộp đơn hủy học phần")
                        .build()
        ));

        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "Hạn chót rút bớt học phần kỳ này là khi nào?",
                          "top_k": 10,
                          "total_found": 0,
                          "blocked": false,
                          "context": "",
                          "results": []
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(requestTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=test-key"))
                .andRespond(withSuccess("""
                        {
                          "candidates": [
                            {
                              "content": {
                                "parts": [
                                  {
                                    "text": "{\\"answer\\":\\"Hạn chót rút bớt học phần tín chỉ đợt 2 là ngày 01/12/2026.\\",\\"sources\\":[],\\"suggestions\\":[]}"
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatDto.Response res = aiChatService.processChat(
                new ChatDto.Request("Hạn chót rút bớt học phần kỳ này là khi nào?", "102230001"));

        mockServer.verify();
        assertThat(res.getAnswer()).contains("01/12/2026");
    }
}
