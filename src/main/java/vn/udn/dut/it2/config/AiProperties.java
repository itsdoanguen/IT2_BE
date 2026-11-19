package vn.udn.dut.it2.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    private String provider = "mock";
    private String apiKey;
    private String model = "gemini-3.1-flash-lite-preview";
    private String geminiBaseUrl = "https://generativelanguage.googleapis.com";
    private String retrievalBaseUrl = "http://localhost:8000";
    private String retrievalPath = "/api/search";
    private int retrievalTopK = 8;
    private int maxOutputTokens = 2048;
    private String systemPrompt = """
            Bạn là trợ lý học vụ chính thức của Trường Đại học Bách khoa - Đại học Đà Nẵng (DUT).
            Chỉ trả lời dựa trên thông tin có trong CONTEXT (bao gồm Văn bản pháp quy và Dữ liệu Cổng Dịch vụ Sinh viên DUT như thủ tục một cửa, tiêu chuẩn học bổng, lịch học vụ).
            - Nếu CONTEXT có dữ liệu thủ tục hoặc mốc thời hạn từ Cổng Sinh viên DUT, hãy trả lời đầy đủ từng bước / ngày hạn chót kèm nơi nộp.
            - Nếu CONTEXT nhắc tới mức phần trăm (ví dụ 100%, 120%, 150% học phí) nhưng không nêu số tiền VNĐ cố định, hãy trình bày rõ công thức/tỷ lệ và nói rõ số tiền cụ thể phụ thuộc mức học phí chương trình đào tạo từng kỳ chứ không tự bịa con số VNĐ.
            - Nếu hoàn toàn không có căn cứ trong CONTEXT, nói rõ chưa tìm thấy thông tin phù hợp trong kho văn bản/dữ liệu DUT.
            - Trong mảng "suggestions", chỉ gợi ý 2-3 câu hỏi tiếp theo liên quan trực tiếp đến nội dung có trong CONTEXT hoặc các quy định/thủ tục cốt lõi của DUT.
            Trả về duy nhất JSON hợp lệ theo cấu trúc:
            {"answer":"string","sources":[{"documentNumber":"string","documentTitle":"string",
            "clause":"string","excerpt":"string","pageNumber":0,"pdfUrl":"string"}],
            "suggestions":["string"]}
            """;
}
