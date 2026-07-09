# LLM-based Document Chunking Tool

## Goal

Build a hybrid document chunking tool that combines rule-based cleaning with LLM-powered semantic cleaning and splitting, producing coherent chunks optimized for RAG retrieval across all document formats (PDF, Word, HTML, Markdown).

## Requirements

* New `LlmDocumentSplitter` implementing existing `DocumentSplitter` interface
* Hybrid architecture: rule-based pre-cleaning → LLM semantic cleaning + splitting in one call
* LLM outputs JSON array of chunks: `[{"content": "...", "title": "..."}]`
* Configurable target chunk size (tokens)
* Preserve atomic elements (TABLE, CODE_BLOCK, IMAGE) — bypass LLM, pass through directly
* Pre-split documents into LLM-manageable segments before sending to LLM
* JSON parsing with retry/fallback for malformed LLM output
* Integrate with existing pipeline seamlessly

## Acceptance Criteria

* [ ] LlmDocumentSplitter implements DocumentSplitter interface
* [ ] Chunks are semantically coherent (no broken sentences/paragraphs)
* [ ] Chunk sizes respect configured token limits
* [ ] Atomic elements (TABLE, CODE_BLOCK, IMAGE) pass through intact
* [ ] Handles LLM output parsing failures gracefully (retry/fallback)
* [ ] Works with existing DocumentReader → CleanerPipeline → Splitter → Embedding pipeline
* [ ] Unit tests covering: normal splitting, oversized chunks, atomic elements, JSON parse errors

## Definition of Done

* Tests added/updated (unit tests with mocked LLM responses)
* Lint / typecheck green
* Works with existing pipeline interfaces
* Example usage in chain-example module

## Technical Approach

### Pipeline Flow

```
Raw Document
  → DocumentReader (existing) → ParsedDocument
  → CleanerPipeline (existing rule-based) → Cleaned ParsedDocument
  → Pre-split into segments (~4000-6000 tokens, using RecursiveCharacterSplitter)
  → For each segment:
      → Atomic elements (TABLE, CODE_BLOCK, IMAGE): pass through via SplitterSupport
      → Text elements: single LLM call → JSON array of chunks
  → Merge all TextChunks → final List<TextChunk>
```

### Key Design Decisions

1. **Hybrid, not pure LLM**: Rule-based cleaners handle deterministic noise; LLM handles semantic understanding
2. **Single LLM call per segment**: Cleaning + splitting in one prompt (not two-pass)
3. **JSON array output**: `[{"content": "...", "title": "..."}]` — structured, parseable
4. **Pre-split before LLM**: Use existing RecursiveCharacterSplitter to create LLM-manageable segments
5. **Atomic element preservation**: TABLE, CODE_BLOCK, IMAGE bypass LLM entirely

### Class Design

```
LlmDocumentSplitter implements DocumentSplitter
  - LLM llm                          // existing interface
  - ContentMeasure measure            // TokenMeasure
  - int targetChunkSize               // max tokens per final chunk
  - int segmentSize                   // pre-split segment size for LLM input (~4000-6000)
  - String promptTemplate             // configurable prompt
  - RecursiveCharacterSplitter preSplitter  // for initial segmentation
```

## Out of Scope

* Multi-document batch processing optimization
* Caching LLM responses
* Streaming LLM output for chunking
* Custom embedding strategy per chunk

## Technical Notes

* Key existing files:
  - DocumentSplitter: chain/src/main/java/com/non/chain/knowledge/DocumentSplitter.java
  - TextChunk: chain/src/main/java/com/non/chain/knowledge/TextChunk.java
  - ContentMeasure: chain/src/main/java/com/non/chain/knowledge/ContentMeasure.java
  - TokenMeasure: chain-document/src/main/java/com/non/chain/document/splitter/TokenMeasure.java
  - RecursiveCharacterSplitter: chain-document/src/main/java/com/non/chain/document/splitter/RecursiveCharacterSplitter.java
  - SplitterSupport: chain-document/src/main/java/com/non/chain/document/splitter/SplitterSupport.java
  - CompositeDocumentSplitter: chain-document/src/main/java/com/non/chain/document/splitter/CompositeDocumentSplitter.java
  - LLM interface: chain/src/main/java/com/non/chain/provider/LLM.java
  - DashscopeLLM: chain/src/main/java/com/non/chain/provider/DashscopeLLM.java
