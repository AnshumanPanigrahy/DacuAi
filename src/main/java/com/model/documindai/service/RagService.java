package com.model.documindai.service;

import com.model.documindai.model.SearchResult;
import com.model.documindai.model.SourceResult;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

@Service
public class RagService {

    private final SearchService searchService;
    private final StreamingChatModel streamingChatModel;

    public RagService(
            SearchService searchService,
            StreamingChatModel streamingChatModel) {

        this.searchService = searchService;
        this.streamingChatModel = streamingChatModel;
    }

    public void streamAnswer(
            String question,
            Consumer<String> onToken,
            Consumer<List<SourceResult>> onSources,
            Consumer<Throwable> onError,
            Runnable onComplete) {

        List<SearchResult> results =
                searchService.search(question);

        if (results.isEmpty()) {

            onToken.accept(
                    "I could not find relevant information in the provided documents."
            );

            onSources.accept(List.of());

            onComplete.run();

            return;
        }


        List<SourceResult> sources = results.stream()
                .map(result -> new SourceResult(
                        result.getDocumentId(),
                        result.getChunkId(),
                        result.getChunkNumber()
                ))
                .toList();


        onSources.accept(sources);


        StringBuilder context =
                new StringBuilder();

        for (SearchResult result : results) {

            context.append("Document ID: ")
                    .append(result.getDocumentId())
                    .append("\n");

            context.append("Chunk Number: ")
                    .append(result.getChunkNumber())
                    .append("\n");

            context.append("Content:\n")
                    .append(result.getText())
                    .append("\n\n---\n\n");
        }

        System.out.print(
                "Context characters: "
                        + context.length()
        );

        System.out.print(
                "Chunks sent to Gemini: "
                        + results.size()
        );


        String prompt = """
                You are DocuMind AI, a document question-answering assistant.

                Answer the user's question using ONLY the provided context.

                Rules:
                - Do not invent information.
                - Do not use outside knowledge.
                - Analyze all relevant chunks together.
                - Combine information from multiple chunks when necessary.
                - If the answer is not present in the context, say:
                  "I could not find the answer in the provided documents."
                - Give a clear and concise answer.

                CONTEXT:
                %s

                USER QUESTION:
                %s

                ANSWER:
                """.formatted(
                context,
                question
        );

        streamingChatModel.chat(
                prompt,
                new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(
                            String partialResponse) {

                        onToken.accept(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(
                            ChatResponse completeResponse) {

                        onComplete.run();
                    }

                    @Override
                    public void onError(
                            Throwable error) {

                        onError.accept(error);
                    }
                }
        );
    }
}