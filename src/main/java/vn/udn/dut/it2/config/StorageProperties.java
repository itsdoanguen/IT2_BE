package vn.udn.dut.it2.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    private String pdfDir = "../crawl-data/output/files";
    private String pdfCacheDir = "./data/pdf-cache";
    private String markdownDir = "../dut_rag_dataset_processed/raw_markdown";
    private String markdownZip = "./data/raw_markdown.zip";
}
