package vn.udn.dut.it2.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import vn.udn.dut.it2.config.AiProperties;
import vn.udn.dut.it2.model.dto.SearchDto;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.RegulationRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private RegulationRepository regulationRepository;

    private MockRestServiceServer mockServer;
    private SearchService searchService;

    private Regulation scholarshipDoc;
    private Regulation trainingDoc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();

        AiProperties props = new AiProperties();
        props.setRetrievalBaseUrl("http://ai:8000");
        props.setRetrievalPath("/api/search");

        searchService = new SearchService(regulationRepository, props, builder.build());

        scholarshipDoc = Regulation.builder()
                .id(1L)
                .documentId("0509")
                .documentNumber("3121/QĐ-BGDĐT")
                .title("Quy chế tổ chức và hoạt động của Quỹ Học bổng Quốc gia")
                .category("HOC_BONG")
                .field("Công tác sinh viên")
                .issuer("Bộ Giáo dục và Đào tạo")
                .status("Còn hiệu lực")
                .issuedDate(LocalDate.of(2026, 10, 27))
                .pdfUrl("http://dut.udn.vn/files/3121.pdf")
                .summary("Quy chế học bổng")
                .tags(List.of("Công tác sinh viên", "Học bổng", "Còn hiệu lực"))
                .build();

        trainingDoc = Regulation.builder()
                .id(2L)
                .documentId("0146")
                .documentNumber("17/2021/TT-BGDĐT")
                .title("Thông tư quy định về chuẩn chương trình đào tạo")
                .category("HOC_VU")
                .field("Đào tạo")
                .issuer("Bộ Giáo dục và Đào tạo")
                .status("Còn hiệu lực")
                .issuedDate(LocalDate.of(2021, 6, 22))
                .pdfUrl("http://dut.udn.vn/files/17_2021.pdf")
                .summary("Chuẩn chương trình đào tạo đại học")
                .tags(List.of("Đào tạo", "Thông tư"))
                .build();
    }

    @Test
    void search_filtersByTagsWithoutQuery() {
        when(regulationRepository.searchRegulations("ALL", null))
                .thenReturn(List.of(scholarshipDoc, trainingDoc));

        SearchDto.Response response = searchService.search("", "ALL", List.of("Học bổng"), 20, true);

        assertThat(response.isSemanticUsed()).isFalse();
        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getItems().get(0).getDocumentNumber()).isEqualTo("3121/QĐ-BGDĐT");
    }

    @Test
    void search_combinesSemanticMatchesAndMetadata() {
        when(regulationRepository.searchRegulations("ALL", null))
                .thenReturn(List.of(scholarshipDoc, trainingDoc));

        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "điều kiện nhận hỗ trợ tài chính",
                          "top_k": 20,
                          "total_found": 1,
                          "embedding_model": "vietnamese-bi-encoder",
                          "blocked": false,
                          "results": [
                            {
                              "chunk_id": "0509_dieu_4_1",
                              "score": 0.87,
                              "distance": 0.13,
                              "content": "Sinh viên đạt học lực Giỏi trở lên.",
                              "full_rag_text": "...",
                              "metadata": {
                                "document_id": "0509",
                                "document_number": "3121/QĐ-BGDĐT",
                                "article_number": "Điều 4",
                                "clause_number": "1"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        SearchDto.Response response = searchService.search(
                "điều kiện nhận hỗ trợ tài chính", "ALL", List.of(), 20, true);

        mockServer.verify();
        assertThat(response.isSemanticUsed()).isTrue();
        assertThat(response.isGuardrailBlocked()).isFalse();
        assertThat(response.getTotal()).isEqualTo(1);
        SearchDto.SearchItem hit = response.getItems().get(0);
        assertThat(hit.getDocumentNumber()).isEqualTo("3121/QĐ-BGDĐT");
        assertThat(hit.getSemanticScore()).isEqualTo(0.87);
        assertThat(hit.getMatchedClause()).isEqualTo("Điều 4, Khoản 1");
    }

    @Test
    void search_fallsBackToDbKeywordWhenAiServiceUnavailable() {
        when(regulationRepository.searchRegulations(any(), any()))
                .thenReturn(List.of(scholarshipDoc, trainingDoc));

        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withException(new IOException("Connection refused")));

        SearchDto.Response response = searchService.search("chương trình đào tạo", "ALL", List.of(), 20, true);

        mockServer.verify();
        assertThat(response.isSemanticUsed()).isFalse();
        assertThat(response.getTotal()).isEqualTo(1);
        assertThat(response.getItems().get(0).getDocumentNumber()).isEqualTo("17/2021/TT-BGDĐT");
    }

    @Test
    void search_returnsBlockedWhenGuardrailRejectsQuery() {
        when(regulationRepository.searchRegulations("ALL", null))
                .thenReturn(List.of(scholarshipDoc));

        mockServer.expect(requestTo("http://ai:8000/api/search"))
                .andRespond(withSuccess("""
                        {
                          "query": "Viết code C++",
                          "top_k": 20,
                          "total_found": 0,
                          "embedding_model": "vietnamese-bi-encoder",
                          "blocked": true,
                          "guardrail_category": "coding",
                          "refusal_message": "Không hỗ trợ viết code.",
                          "results": []
                        }
                        """, MediaType.APPLICATION_JSON));

        SearchDto.Response response = searchService.search("Viết code C++", "ALL", List.of(), 20, true);

        mockServer.verify();
        assertThat(response.isGuardrailBlocked()).isTrue();
        assertThat(response.getGuardrailMessage()).isEqualTo("Không hỗ trợ viết code.");
        assertThat(response.getItems()).isEmpty();
    }

    @Test
    void search_paginatesResultsAndReportsTotalItemsAndTags() {
        when(regulationRepository.searchRegulations("ALL", null))
                .thenReturn(List.of(scholarshipDoc, trainingDoc));

        SearchDto.Response page1 = searchService.search("", "ALL", List.of(), 1, 1, false);
        assertThat(page1.getPage()).isEqualTo(1);
        assertThat(page1.getSize()).isEqualTo(1);
        assertThat(page1.getTotalItems()).isEqualTo(2);
        assertThat(page1.getTotalPages()).isEqualTo(2);
        assertThat(page1.getItems()).hasSize(1);
        assertThat(page1.getItems().get(0).getDocumentNumber()).isEqualTo("3121/QĐ-BGDĐT");
        assertThat(page1.getAvailableTags()).contains("Học bổng", "Đào tạo");

        SearchDto.Response page2 = searchService.search("", "ALL", List.of(), 2, 1, false);
        assertThat(page2.getPage()).isEqualTo(2);
        assertThat(page2.getTotalItems()).isEqualTo(2);
        assertThat(page2.getItems()).hasSize(1);
        assertThat(page2.getItems().get(0).getDocumentNumber()).isEqualTo("17/2021/TT-BGDĐT");
    }
}
