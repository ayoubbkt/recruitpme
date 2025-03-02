package com.recruitpme.apigateway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.recruitpme.apigateway.model.AnalysisResult;
import com.recruitpme.apigateway.model.CVIndex;
import com.recruitpme.apigateway.repository.CVRepository;
import com.recruitpme.apigateway.client.AIServiceClient;
import com.recruitpme.apigateway.exception.CVAnalysisException;
import com.recruitpme.apigateway.utils.TextExtractor; // Added import for TextExtractor
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CVAnalysisServiceImpl implements CVAnalysisService {

    private final CVRepository cvRepository;
    private final ElasticsearchOperations elasticsearchOperations;
    private final AIServiceClient aiServiceClient;
    private final MinioClient minioClient;

    @Async
    public CompletableFuture<AnalysisResult> analyzeCV(MultipartFile file, String jobId) {
        try {
            // Upload vers MinIO
            String objectName = UUID.randomUUID().toString();
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket("cv-bucket")
                    .object(objectName)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .build()
            );

            // Extraction du texte
            String text = TextExtractor.extract(file.getInputStream());
            
            // Appel au service IA
            AIServiceClient.AnalysisRequest request = new AIServiceClient.AnalysisRequest(
                text, 
                jobId
            );
            
            AnalysisResult result = aiServiceClient.analyze(request);
            
            // Indexation dans Elasticsearch
            CVIndex cvIndex = new CVIndex(
                objectName,
                result.getSkills(),
                result.getExperience(),
                result.getScore()
            );
            
            elasticsearchOperations.save(cvIndex);
            
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            throw new CVAnalysisException("Erreur d'analyse du CV", e);
        }
    }

    @Override
    public Page<CVIndex> searchCVs(String query, Pageable pageable) {
        // Utiliser StringQuery pour une recherche multi-champs
        String queryString = String.format("{\"multi_match\": {\"query\": \"%s\", \"fields\": [\"skills\", \"experience\"]}}", query);
        Query searchQuery = new StringQuery(queryString).setPageable(pageable);
        
        SearchHits<CVIndex> searchHits = elasticsearchOperations.search(searchQuery, CVIndex.class);
        return searchHits.getSearchHits().stream()
            .map(hit -> hit.getContent())
            .collect(Collectors.toList())
            .stream()
            .collect(Collectors.collectingAndThen(
                Collectors.toList(),
                list -> new PageImpl<>(list, pageable, searchHits.getTotalHits())));
    }
}