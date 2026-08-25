package com.claw.assistant.rag;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不依赖向量数据库的极简 RAG：加载 Markdown，按段落切块，再按关键词和中文二元词排序。
 */
@Service
public class KeywordRagService {
    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9_+-]{2,}");
    private final List<KnowledgeDocument> chunks;

    @Autowired
    public KeywordRagService(ResourcePatternResolver resolver) throws IOException {
        this(loadDocuments(resolver));
    }

    KeywordRagService(List<KnowledgeDocument> documents) {
        this.chunks = documents.stream()
                .flatMap(document -> split(document).stream())
                .toList();
    }

    public List<KnowledgeSearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        Set<String> queryTerms = terms(query);
        return chunks.stream()
                .map(chunk -> new KnowledgeSearchResult(
                        chunk.source(), chunk.content(), score(queryTerms, chunk.content())))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingInt(KnowledgeSearchResult::score).reversed()
                        .thenComparing(KnowledgeSearchResult::source))
                .limit(topK)
                .toList();
    }

    public String buildContext(String query, int topK) {
        List<KnowledgeSearchResult> results = search(query, topK);
        if (results.isEmpty()) {
            return "未检索到相关知识库片段。";
        }
        StringBuilder context = new StringBuilder();
        for (int index = 0; index < results.size(); index++) {
            KnowledgeSearchResult result = results.get(index);
            context.append("[").append(index + 1).append("] 来源：")
                    .append(result.source()).append('\n')
                    .append(result.content()).append("\n\n");
        }
        return context.toString().trim();
    }

    private static List<KnowledgeDocument> loadDocuments(ResourcePatternResolver resolver) throws IOException {
        Resource[] resources = resolver.getResources("classpath*:knowledge-base/*.md");
        List<KnowledgeDocument> documents = new ArrayList<>();
        for (Resource resource : resources) {
            try (var input = resource.getInputStream()) {
                documents.add(new KnowledgeDocument(
                        resource.getFilename(),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return documents;
    }

    private static List<KnowledgeDocument> split(KnowledgeDocument document) {
        List<KnowledgeDocument> result = new ArrayList<>();
        for (String block : document.content().split("(?:\\R\\s*){2,}")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty()) {
                result.add(new KnowledgeDocument(document.source(), trimmed));
            }
        }
        return result;
    }

    private static int score(Set<String> queryTerms, String content) {
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : queryTerms) {
            if (normalizedContent.contains(term)) {
                score += term.length() >= 4 ? 3 : 1;
            }
        }
        return score;
    }

    private static Set<String> terms(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = LATIN_TOKEN.matcher(normalized);
        while (matcher.find()) {
            terms.add(matcher.group());
        }
        String chinese = normalized.replaceAll("[^\\p{IsHan}]", "");
        for (int index = 0; index + 1 < chinese.length(); index++) {
            terms.add(chinese.substring(index, index + 2));
        }
        return terms;
    }
}
