package vn.udn.dut.it2.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import vn.udn.dut.it2.config.StorageProperties;
import vn.udn.dut.it2.model.entity.Regulation;
import vn.udn.dut.it2.repository.RegulationRepository;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegulationService {

    private static final HttpClient PDF_HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final RegulationRepository regulationRepository;
    private final StorageProperties storageProperties;

    public List<Regulation> search(String category, String keyword) {
        return regulationRepository.searchRegulations(category, keyword);
    }

    public Optional<Regulation> getById(Long id) {
        return regulationRepository.findById(id);
    }

    public Optional<Regulation> getByDocumentNumber(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) {
            return Optional.empty();
        }
        String trimmed = documentNumber.trim();
        Optional<Regulation> exact = regulationRepository.findFirstByDocumentNumberIgnoreCase(trimmed);
        if (exact.isPresent()) {
            return exact;
        }
        String stripped = trimmed
                .replaceFirst("(?iu)^(?:quyết định|quyet dinh|thông tư|thong tu|công văn|cong van|nghị định|nghi dinh|văn bản|van ban|qđ|qd|tt|cv|số|so)\\s+", "")
                .trim();
        if (!stripped.isEmpty() && !stripped.equalsIgnoreCase(trimmed)) {
            return regulationRepository.findFirstByDocumentNumberIgnoreCase(stripped);
        }
        return Optional.empty();
    }

    public List<Regulation> getAll() {
        return regulationRepository.findAll();
    }

    public Optional<Resource> resolvePdfResource(Long id) {
        return regulationRepository.findById(id).flatMap(reg -> {
            Optional<Path> localFile = findFileByPrefix(storageProperties.getPdfDir(), reg.getDocumentId(), ".pdf");
            if (localFile.isPresent()) {
                return Optional.of(new FileSystemResource(localFile.get()));
            }
            Optional<Path> cachedOrFetched = resolveOrFetchCachedPdf(reg);
            return cachedOrFetched.map(FileSystemResource::new);
        });
    }

    public Optional<RegulationContent> getContent(Long id) {
        return regulationRepository.findById(id).map(reg -> {
            String markdown = readMarkdownFromDisk(reg.getDocumentId())
                    .or(() -> readMarkdownFromZip(reg.getDocumentId()))
                    .orElse(null);
            return new RegulationContent(
                    reg.getId(),
                    reg.getDocumentNumber(),
                    reg.getTitle(),
                    reg.getContentHtml(),
                    markdown);
        });
    }

    private Optional<Path> resolveOrFetchCachedPdf(Regulation reg) {
        String cacheDirRaw = storageProperties.getPdfCacheDir();
        if (cacheDirRaw == null || cacheDirRaw.isBlank()) {
            return Optional.empty();
        }
        Path cacheDir = Path.of(cacheDirRaw).toAbsolutePath().normalize();
        String cacheKey = (reg.getDocumentId() != null && !reg.getDocumentId().isBlank())
                ? "doc_" + reg.getDocumentId().trim()
                : "reg_" + reg.getId();
        Path cachedPath = cacheDir.resolve(cacheKey + ".pdf");
        try {
            if (Files.isRegularFile(cachedPath) && Files.size(cachedPath) > 0) {
                return Optional.of(cachedPath);
            }
        } catch (IOException ignored) {
            // Re-fetch if stat fails
        }

        String pdfUrl = reg.getPdfUrl();
        if (pdfUrl == null || (!pdfUrl.startsWith("http://") && !pdfUrl.startsWith("https://"))) {
            return Optional.empty();
        }

        try {
            URI encodedUri = URI.create(encodeHttpUrl(pdfUrl));
            HttpRequest req = HttpRequest.newBuilder(encodedUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "DUT-IT2-Portal/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = PDF_HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                Files.createDirectories(cacheDir);
                Path tmpFile = Files.createTempFile(cacheDir, cacheKey + "_", ".tmp");
                Files.write(tmpFile, resp.body());
                Files.move(tmpFile, cachedPath, StandardCopyOption.REPLACE_EXISTING);
                return Optional.of(cachedPath);
            }
            log.warn("Remote PDF fetch returned status {} for regulation id={} url={}",
                    resp.statusCode(), reg.getId(), pdfUrl);
        } catch (Exception ex) {
            log.warn("Failed to fetch remote PDF for regulation id={} url={}: {}",
                    reg.getId(), pdfUrl, ex.getMessage());
        }
        return Optional.empty();
    }

    static String encodeHttpUrl(String rawUrl) {
        StringBuilder sb = new StringBuilder(rawUrl.length() + 16);
        int i = 0;
        while (i < rawUrl.length()) {
            char ch = rawUrl.charAt(i);
            // Preserve valid existing %XX escapes without double-encoding
            if (ch == '%' && i + 2 < rawUrl.length()
                    && isHex(rawUrl.charAt(i + 1)) && isHex(rawUrl.charAt(i + 2))) {
                sb.append(rawUrl, i, i + 3);
                i += 3;
                continue;
            }
            if (ch <= 0x7F && !Character.isWhitespace(ch) && ch != '"' && ch != '<' && ch != '>') {
                sb.append(ch);
                i++;
                continue;
            }
            int cp = rawUrl.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append(String.format("%%%02X", b & 0xFF));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private Optional<String> readMarkdownFromDisk(String documentId) {
        return findFileByPrefix(storageProperties.getMarkdownDir(), documentId, ".md")
                .map(path -> {
                    try {
                        return Files.readString(path, StandardCharsets.UTF_8);
                    } catch (IOException ex) {
                        log.warn("Could not read markdown file {}: {}", path, ex.getMessage());
                        return null;
                    }
                });
    }

    private Optional<String> readMarkdownFromZip(String documentId) {
        String zipRaw = storageProperties.getMarkdownZip();
        if (zipRaw == null || zipRaw.isBlank() || documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        Path zipPath = Path.of(zipRaw).toAbsolutePath().normalize();
        if (!Files.isRegularFile(zipPath)) {
            return Optional.empty();
        }
        String prefix = documentId.trim() + "_";
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            Optional<? extends ZipEntry> match = zipFile.stream()
                    .filter(e -> !e.isDirectory())
                    .filter(e -> {
                        String name = Path.of(e.getName()).getFileName().toString();
                        return name.startsWith(prefix) && name.toLowerCase().endsWith(".md");
                    })
                    .findFirst();
            if (match.isPresent()) {
                try (InputStream is = zipFile.getInputStream(match.get())) {
                    return Optional.of(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ex) {
            log.warn("Failed to read markdown from zip {}: {}", zipPath, ex.getMessage());
        }
        return Optional.empty();
    }

    private Optional<Path> findFileByPrefix(String dirRaw, String documentId, String extension) {
        if (dirRaw == null || dirRaw.isBlank() || documentId == null || documentId.isBlank()) {
            return Optional.empty();
        }
        Path dir = Path.of(dirRaw).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String prefix = documentId.trim() + "_";
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.toLowerCase().endsWith(extension);
                    })
                    .findFirst();
        } catch (IOException ex) {
            log.warn("Failed to list directory {}: {}", dir, ex.getMessage());
            return Optional.empty();
        }
    }

    public record RegulationContent(
            Long id,
            String documentNumber,
            String title,
            String contentHtml,
            String markdown) {}
}
