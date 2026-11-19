package vn.udn.dut.it2.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.udn.dut.it2.model.dto.SearchDto;
import vn.udn.dut.it2.service.SearchService;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Tag(name = "7. Hybrid Search", description = "API Tìm kiếm tổng hợp kết hợp Bộ lọc DB (category/tag) và Semantic Search (IT2_AI)")
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    @Operation(summary = "Tìm kiếm tổng hợp văn bản quy chế theo từ khóa ngữ nghĩa, danh mục và tag")
    public ResponseEntity<SearchDto.Response> searchRegulations(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "ALL") String category,
            @RequestParam(required = false) List<String> tag,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "true") boolean semantic) {

        String effectiveQuery = (q != null && !q.isBlank()) ? q : keyword;
        List<String> mergedTags = new ArrayList<>();
        if (tag != null) {
            mergedTags.addAll(tag);
        }
        if (tags != null) {
            mergedTags.addAll(tags);
        }

        int effectiveSize = size != null ? size : (limit != null ? limit : 12);
        return ResponseEntity.ok(searchService.search(effectiveQuery, category, mergedTags, page, effectiveSize, semantic));
    }
}
