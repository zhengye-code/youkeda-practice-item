package com.claw.assistant.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRagServiceTest {

    @Test
    void retrievesRelevantMarkdownChunkAndKeepsSource() {
        KeywordRagService service = new KeywordRagService(List.of(
                new KnowledgeDocument("course.md", "RAG 会先检索知识库，再增强 Prompt。\n\n天气工具用于查询天气。"),
                new KnowledgeDocument("skill.md", "Skill 是可复用的业务流程。")
        ));

        List<KnowledgeSearchResult> results = service.search("RAG 如何增强知识库回答", 2);

        assertEquals("course.md", results.getFirst().source());
        assertTrue(results.getFirst().content().contains("增强 Prompt"));
        assertTrue(service.buildContext("RAG 如何增强知识库回答", 1).contains("来源：course.md"));
    }

    @Test
    void returnsNoResultsForBlankQuery() {
        KeywordRagService service = new KeywordRagService(List.of(
                new KnowledgeDocument("course.md", "RAG 课程资料")
        ));

        assertTrue(service.search(" ", 3).isEmpty());
    }
}
