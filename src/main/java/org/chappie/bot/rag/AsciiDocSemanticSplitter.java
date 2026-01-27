package org.chappie.bot.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic splitter for AsciiDoc documents that splits by headers/sections.
 *
 * AsciiDoc headers:
 * = Document Title (level 0)
 * == Section (level 1)
 * === Subsection (level 2)
 * ==== Subsubsection (level 3)
 * ===== Paragraph (level 4)
 */
public class AsciiDocSemanticSplitter implements DocumentSplitter {

    private static final Pattern HEADER_PATTERN = Pattern.compile("^(=+)\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final int MIN_SECTION_SIZE = 300; // Merge sections smaller than this

    private final int maxChunkSize;
    private final int chunkOverlap;
    private final DocumentSplitter fallbackSplitter;

    public AsciiDocSemanticSplitter(int maxChunkSize, int chunkOverlap) {
        this.maxChunkSize = maxChunkSize;
        this.chunkOverlap = chunkOverlap;
        this.fallbackSplitter = DocumentSplitters.recursive(maxChunkSize, chunkOverlap);
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        Metadata baseMetadata = document.metadata();

        List<Section> sections = parseIntoSections(text);

        if (sections.isEmpty()) {
            // No headers found, fall back to recursive splitting
            return fallbackSplitter.split(document);
        }

        List<TextSegment> chunks = new ArrayList<>();

        for (Section section : sections) {
            if (section.content.length() <= maxChunkSize) {
                // Section fits in one chunk
                chunks.add(createChunk(section, baseMetadata));
            } else {
                // Section too large, split it with fallback splitter
                Document sectionDoc = Document.from(section.content, baseMetadata);
                List<TextSegment> subChunks = fallbackSplitter.split(sectionDoc);

                // Add section metadata to each sub-chunk
                for (int i = 0; i < subChunks.size(); i++) {
                    TextSegment subChunk = subChunks.get(i);
                    Metadata enriched = enrichMetadata(subChunk.metadata(), section, i, subChunks.size());
                    chunks.add(TextSegment.from(subChunk.text(), enriched));
                }
            }
        }

        // Add overlap between sections if configured
        if (chunkOverlap > 0) {
            chunks = addCrossSectionOverlap(chunks, sections);
        }

        return chunks;
    }

    private List<Section> parseIntoSections(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADER_PATTERN.matcher(text);

        List<HeaderMatch> headers = new ArrayList<>();
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();
            int start = matcher.start();
            headers.add(new HeaderMatch(level, title, start));
        }

        if (headers.isEmpty()) {
            return sections;
        }

        // Build section hierarchy
        for (int i = 0; i < headers.size(); i++) {
            HeaderMatch current = headers.get(i);
            int contentStart = text.indexOf('\n', current.position) + 1;
            int contentEnd = (i + 1 < headers.size()) ? headers.get(i + 1).position : text.length();

            String content = text.substring(contentStart, contentEnd).trim();

            if (!content.isEmpty()) {
                // Build header path (e.g., "Getting Started > REST > JSON")
                String headerPath = buildHeaderPath(headers, i);
                sections.add(new Section(current.level, current.title, content, headerPath));
            }
        }

        // Post-process: merge small sections to preserve context
        return mergeSmallSections(sections);
    }

    private String buildHeaderPath(List<HeaderMatch> headers, int currentIndex) {
        List<String> path = new ArrayList<>();
        HeaderMatch current = headers.get(currentIndex);

        // Add current header
        path.add(current.title);

        // Look backwards for parent headers
        for (int i = currentIndex - 1; i >= 0; i--) {
            HeaderMatch prev = headers.get(i);
            if (prev.level < current.level) {
                path.add(0, prev.title);
                current = prev;
            }
        }

        return String.join(" > ", path);
    }

    /**
     * Merge small sections together to preserve context.
     * Sections < MIN_SECTION_SIZE are merged with adjacent sections.
     */
    private List<Section> mergeSmallSections(List<Section> sections) {
        if (sections.isEmpty()) {
            return sections;
        }

        List<Section> merged = new ArrayList<>();
        Section pending = null;

        for (int i = 0; i < sections.size(); i++) {
            Section current = sections.get(i);

            if (pending == null) {
                pending = current;
            } else {
                // Check if we should merge
                boolean shouldMerge = false;

                // Merge if pending section is too small
                if (pending.content.length() < MIN_SECTION_SIZE) {
                    shouldMerge = true;
                }

                // Don't merge across major section boundaries (level 1 or 2)
                // unless the section is very small (< 200 chars)
                if (shouldMerge && (pending.level <= 2 || current.level <= 2)) {
                    if (pending.content.length() >= 200) {
                        shouldMerge = false;
                    }
                }

                // Don't merge if combined size would exceed maxChunkSize
                if (shouldMerge && (pending.content.length() + current.content.length() > maxChunkSize)) {
                    shouldMerge = false;
                }

                if (shouldMerge) {
                    // Merge current into pending
                    pending = mergeTwoSections(pending, current);
                } else {
                    // Save pending and start new
                    merged.add(pending);
                    pending = current;
                }
            }
        }

        // Don't forget the last pending section
        if (pending != null) {
            merged.add(pending);
        }

        return merged;
    }

    /**
     * Merge two sections into one, combining content and metadata.
     */
    private Section mergeTwoSections(Section first, Section second) {
        // Combine content with double newline separator
        String combinedContent = first.content + "\n\n" + second.content;

        // Use the first section's level (it's the parent)
        int combinedLevel = first.level;

        // Combine titles
        String combinedTitle = first.title + " + " + second.title;

        // Combine header paths
        String combinedPath = first.headerPath + " | " + second.headerPath;

        return new Section(combinedLevel, combinedTitle, combinedContent, combinedPath);
    }

    private TextSegment createChunk(Section section, Metadata baseMetadata) {
        Metadata enriched = enrichMetadata(baseMetadata, section, 0, 1);
        return TextSegment.from(section.content, enriched);
    }

    private Metadata enrichMetadata(Metadata base, Section section, int partIndex, int totalParts) {
        Map<String, Object> metadata = new LinkedHashMap<>(base.toMap());

        metadata.put("section_title", section.title);
        metadata.put("section_level", section.level);
        metadata.put("section_path", section.headerPath);

        if (totalParts > 1) {
            metadata.put("section_part", (partIndex + 1) + "/" + totalParts);
        }

        return new Metadata(metadata);
    }

    private List<TextSegment> addCrossSectionOverlap(List<TextSegment> chunks, List<Section> sections) {
        // For simplicity, we'll skip cross-section overlap for now
        // since sections are naturally related through headers
        // This could be enhanced later if needed
        return chunks;
    }

    private static class HeaderMatch {
        final int level;
        final String title;
        final int position;

        HeaderMatch(int level, String title, int position) {
            this.level = level;
            this.title = title;
            this.position = position;
        }
    }

    private static class Section {
        final int level;
        final String title;
        final String content;
        final String headerPath;

        Section(int level, String title, String content, String headerPath) {
            this.level = level;
            this.title = title;
            this.content = content;
            this.headerPath = headerPath;
        }
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        List<TextSegment> result = new ArrayList<>();
        for (Document document : documents) {
            result.addAll(split(document));
        }
        return result;
    }
}
