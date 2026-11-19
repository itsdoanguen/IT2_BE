package vn.udn.dut.it2.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import vn.udn.dut.it2.config.StorageProperties;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.RegulationRepository;

import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulationServiceTest {

    @Mock
    private RegulationRepository repository;

    @TempDir
    Path tempDir;

    @Test
    void resolvePdfAndContent_findsMatchingDocumentFilesByPrefix() throws Exception {
        Path pdfDir = Files.createDirectory(tempDir.resolve("pdfs"));
        Path mdDir = Files.createDirectory(tempDir.resolve("md"));
        Files.writeString(pdfDir.resolve("0509_3121_QĐ_BGDĐT.pdf"), "%PDF-1.4 mock");
        Files.writeString(mdDir.resolve("0509_3121_QĐ_BGDĐT.md"), "# Điều 4\nTiêu chuẩn học bổng");

        StorageProperties props = new StorageProperties();
        props.setPdfDir(pdfDir.toString());
        props.setMarkdownDir(mdDir.toString());

        Regulation reg = Regulation.builder()
                .id(10L)
                .documentId("0509")
                .documentNumber("3121/QĐ-BGDĐT")
                .title("Quy chế Quỹ Học bổng Quốc gia")
                .build();
        when(repository.findById(10L)).thenReturn(Optional.of(reg));

        RegulationService service = new RegulationService(repository, props);

        Optional<Resource> pdf = service.resolvePdfResource(10L);
        assertThat(pdf).isPresent();
        assertThat(pdf.get().getFilename()).isEqualTo("0509_3121_QĐ_BGDĐT.pdf");

        Optional<RegulationService.RegulationContent> content = service.getContent(10L);
        assertThat(content).isPresent();
        assertThat(content.get().markdown()).contains("Tiêu chuẩn học bổng");
    }

    @Test
    void getContent_fallsBackToBundledMarkdownZipWhenDiskDirMissing() throws Exception {
        Path zipPath = tempDir.resolve("raw_markdown.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()), StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry("0508_6000_QĐ_ĐHBK.md"));
            zos.write("# Quy chế tạm thời\nĐiều 1. Phạm vi".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        StorageProperties props = new StorageProperties();
        props.setMarkdownDir(tempDir.resolve("non-existent").toString());
        props.setMarkdownZip(zipPath.toString());

        Regulation reg = Regulation.builder()
                .id(11L)
                .documentId("0508")
                .documentNumber("6000/QĐ-ĐHBK")
                .title("Quy chế tạm thời")
                .build();
        when(repository.findById(11L)).thenReturn(Optional.of(reg));

        RegulationService service = new RegulationService(repository, props);
        Optional<RegulationService.RegulationContent> content = service.getContent(11L);

        assertThat(content).isPresent();
        assertThat(content.get().markdown()).contains("Quy chế tạm thời");
    }

    @Test
    void encodeHttpUrl_encodesSpacesAndVietnameseWithoutDoubleEncoding() {
        String raw = "http://dut.udn.vn/Files/admin/files/VanbanPhapquy/18.Quyet%20dinh_1414_QĐ-ĐHBK_Quy đinh.pdf";
        String encoded = RegulationService.encodeHttpUrl(raw);
        URI uri = URI.create(encoded);
        assertThat(uri.toASCIIString()).doesNotContain("%2520");
        assertThat(encoded).contains("18.Quyet%20dinh_1414_Q%C4%90-%C4%90HBK_Quy%20%C4%91inh.pdf");
    }
}
