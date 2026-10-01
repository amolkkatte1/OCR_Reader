package com.OCR.Reader.service.impl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.OCR.Reader.constants.AppConstants;
import com.OCR.Reader.pojo.OCRResult;
import com.OCR.Reader.service.OCRService;
import com.OCR.Reader.util.OCRProcessor;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OCRServiceImpl implements OCRService {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");

    @Override
    public OCRResult processImage(MultipartFile file, List<String> keys, int columnCount) {

        OCRResult result = new OCRResult();

        File tempFile = null;

        try {

            if (file == null || file.isEmpty()) {

                result.setStatus(AppConstants.ERROR);

                return result;
            }

            if (keys == null || keys.isEmpty()) {

                result.setStatus(AppConstants.ERROR);

                return result;
            }

            if (columnCount < 1) {
                columnCount = 1;
            }

            //log.info("==================================================");

            //log.info("OCR PROCESSING STARTED");

            //log.info("Column Count: {}", columnCount);

            //log.info("Requested Keys: {}", keys);

            //log.info("==================================================");

            tempFile = File.createTempFile("ocr", ".tmp");

            file.transferTo(tempFile);

            OCRProcessor ocrProcessor = new OCRProcessor();

            String extractedText = ocrProcessor.extractTextFromImage(tempFile);

            //log.info("OCR TEXT:\n{}", extractedText);

            Map<String, String> keyValues = extractKeyValues(extractedText, keys, columnCount);

            result.setData(keyValues);

            result.setStatus(AppConstants.SUCCESS);

            //log.info("FINAL OCR RESULT: {}", keyValues);

        } catch (IOException e) {

            log.error("Error while processing OCR image", e);

            result.setStatus(AppConstants.ERROR);

        } catch (Exception e) {

            log.error("Unexpected OCR error", e);

            result.setStatus(AppConstants.ERROR);

        } finally {

            if (tempFile != null && tempFile.exists()) {

                if (!tempFile.delete()) {

                    log.warn("Unable to delete temp file: {}", tempFile.getAbsolutePath());
                }
            }
        }

        return result;
    }

    /*
     * ============================================================ MAIN EXTRACTION
     * ============================================================
     */

    private Map<String, String> extractKeyValues(String extractedText, List<String> keys, int columnCount) {

        Map<String, String> result = new LinkedHashMap<>();

        if (extractedText == null || extractedText.isBlank() || keys == null || keys.isEmpty()) {

            return result;
        }

        List<String> cleanedKeys = cleanKeys(keys);

        if (cleanedKeys.isEmpty()) {
            return result;
        }

        String text = normalizeOCRText(extractedText);

        String[] lines = text.split("\\r?\\n");

        /*
         * -------------------------------------------------------- IMPORTANT
         *
         * Every requested key searches ALL columns.
         *
         * Example:
         *
         * columnCount = 3
         *
         * WBC 7.2 HGB 13.5 RBC 4.27
         *
         * WBC -> column 1 HGB -> column 2 RBC -> column 3
         *
         * But we do NOT assume this.
         *
         * Every key searches:
         *
         * column 1 column 2 column 3
         *
         * --------------------------------------------------------
         */

        for (String key : cleanedKeys) {

            if (result.containsKey(key)) {
                continue;
            }

            String value = findKeyInAllColumns(key, lines, cleanedKeys, columnCount);

            if (value != null && !value.isBlank()) {

                result.put(key, value);

                //log.info("FOUND -> {} = {}", key, value);
            } else {

                log.warn("NOT FOUND -> {}", key);
            }
        }

        /*
         * Final fallback for values that appear on the following OCR line.
         */
        extractValuesFromNextLines(lines, cleanedKeys, result);

        return result;
    }

    /*
     * ============================================================ SEARCH KEY IN
     * ALL COLUMNS ============================================================
     */

    private String findKeyInAllColumns(String key, String[] lines, List<String> allKeys, int columnCount) {

        if (key == null || key.isBlank() || lines == null) {

            return null;
        }

        /*
         * Search every OCR line.
         */
        for (String line : lines) {

            if (line == null || line.isBlank()) {

                continue;
            }

            /*
             * Create exactly the logical columns from this OCR line.
             */
            List<String> columns = createColumns(line, allKeys, columnCount);

            log.debug("OCR LINE: [{}]", line);

            log.debug("COLUMNS: {}", columns);

            /*
             * SEARCH KEY IN EVERY COLUMN
             */
            for (int i = 0; i < columns.size(); i++) {

                String column = columns.get(i);

                if (column == null || column.isBlank()) {

                    continue;
                }

                log.debug("Searching key [{}] in column {} -> [{}]", key, i + 1, column);

                String value = extractValueFromColumn(column, key, allKeys);

                if (value != null && !value.isBlank()) {

                    //log.info("KEY [{}] FOUND IN COLUMN {} -> {}", key, i + 1, value);

                    return value;
                }
            }
        }

        /*
         * -------------------------------------------------------- COMPLETE LINE
         * FALLBACK --------------------------------------------------------
         *
         * Example:
         *
         * WBC 7.2 HGB 13.5 RBC 4.27
         *
         * If column detection fails, search the complete OCR line.
         */
        for (String line : lines) {

            if (line == null || line.isBlank()) {

                continue;
            }

            String value = extractValueFromColumn(line, key, allKeys);

            if (value != null && !value.isBlank()) {

                //log.info("KEY [{}] FOUND IN COMPLETE LINE -> {}", key, value);

                return value;
            }
        }

        return null;
    }

    /*
     * ============================================================ CREATE COLUMNS
     * ============================================================
     *
     * Example:
     *
     * WBC 7.2 HGB 13.5 RBC 4.27
     *
     * columnCount = 3
     *
     * Result:
     *
     * [ "WBC 7.2", "HGB 13.5", "RBC 4.27" ]
     *
     * ============================================================
     */

    private List<String> createColumns(String line, List<String> keys, int columnCount) {

        List<String> columns = new ArrayList<>();

        if (line == null || line.isBlank()) {

            return columns;
        }

        String text = line.trim();

        if (columnCount <= 1) {

            columns.add(text);

            return columns;
        }

        /*
         * -------------------------------------------------------- FIRST METHOD
         *
         * Find all requested keys in the line.
         *
         * Example:
         *
         * WBC 7.2 HGB 13.5 RBC 4.27
         *
         * Positions:
         *
         * WBC -> 0 HGB -> ... RBC -> ...
         *
         * Create a column from one key to the next key.
         * --------------------------------------------------------
         */

        List<KeyPosition> positions = findAllKeyPositions(text, keys);

        positions.sort(Comparator.comparingInt(KeyPosition::getIndex));

        List<KeyPosition> uniquePositions = removeDuplicatePositions(positions);

        if (!uniquePositions.isEmpty()) {

            for (int i = 0; i < uniquePositions.size(); i++) {

                int start = uniquePositions.get(i).getIndex();

                int end = text.length();

                if (i + 1 < uniquePositions.size()) {

                    end = uniquePositions.get(i + 1).getIndex();
                }

                if (start >= end) {
                    continue;
                }

                String column = text.substring(start, end).trim();

                if (!column.isEmpty()) {

                    columns.add(column);
                }
            }

            /*
             * If we found the expected number of columns, return them.
             */
            if (columns.size() == columnCount) {

                return columns;
            }

            /*
             * If fewer columns were detected, continue with whitespace fallback.
             */
        }

        /*
         * -------------------------------------------------------- SECOND METHOD
         *
         * OCR normally puts large spaces between columns.
         *
         * Example:
         *
         * WBC 7.2 HGB 13.5 RBC 4.27
         *
         * Split on 3 or more spaces.
         * --------------------------------------------------------
         */

        columns.clear();

        String[] spaceColumns = text.split("\\s{3,}");

        for (String column : spaceColumns) {

            if (column != null && !column.isBlank()) {

                columns.add(column.trim());
            }
        }

        if (columns.size() == columnCount) {

            return columns;
        }

        /*
         * -------------------------------------------------------- THIRD METHOD
         *
         * If OCR has inconsistent spacing, use the detected key positions again.
         *
         * We don't assign a key to a fixed column. We simply return all detected key
         * groups. --------------------------------------------------------
         */

        if (!uniquePositions.isEmpty()) {

            columns.clear();

            for (int i = 0; i < uniquePositions.size(); i++) {

                int start = uniquePositions.get(i).getIndex();

                int end = text.length();

                if (i + 1 < uniquePositions.size()) {

                    end = uniquePositions.get(i + 1).getIndex();
                }

                String column = text.substring(start, end).trim();

                if (!column.isEmpty()) {

                    columns.add(column);
                }
            }

            return columns;
        }

        /*
         * -------------------------------------------------------- LAST FALLBACK
         *
         * Treat complete line as one column.
         * --------------------------------------------------------
         */

        columns.clear();

        columns.add(text);

        return columns;
    }

    /*
     * ============================================================ EXTRACT VALUE
     * FROM COLUMN ============================================================
     */

    private String extractValueFromColumn(String column, String requestedKey, List<String> allKeys) {

        if (column == null || column.isBlank() || requestedKey == null || requestedKey.isBlank()) {

            return null;
        }

        /*
         * Find the actual key position.
         */
        KeyMatch keyMatch = findKeyMatch(column, requestedKey);

        if (keyMatch == null) {

            return null;
        }

        /*
         * IMPORTANT:
         *
         * Use actual OCR end position.
         *
         * DO NOT use:
         *
         * key.length()
         */
        int valueStart = keyMatch.getEndIndex();

        if (valueStart >= column.length()) {

            return null;
        }

        String valuePart = column.substring(valueStart);

        /*
         * Find next requested key.
         *
         * This prevents taking numbers belonging to the next test.
         */
        KeyMatch nextKey = findNextKey(valuePart, allKeys);

        if (nextKey != null) {

            valuePart = valuePart.substring(0, nextKey.getStartIndex());
        }

        valuePart = cleanValue(valuePart);

        String value = extractNumber(valuePart);

        if (value != null) {

            return value;
        }

        return null;
    }

    /*
     * ============================================================ FIND KEY MATCH
     * ============================================================
     */

    private KeyMatch findKeyMatch(String text, String key) {

        if (text == null || text.isBlank() || key == null || key.isBlank()) {

            return null;
        }

        String normalizedText = normalizeForKeyMatching(text);

        String normalizedKey = normalizeForKeyMatching(key);

        if (normalizedText.isEmpty() || normalizedKey.isEmpty()) {

            return null;
        }

        int normalizedStart = normalizedText.indexOf(normalizedKey);

        if (normalizedStart < 0) {

            return null;
        }

        int normalizedEnd = normalizedStart + normalizedKey.length();

        int originalStart = mapNormalizedIndexToOriginal(text, normalizedStart);

        int originalEnd = mapNormalizedEndToOriginal(text, normalizedEnd);

        if (originalStart < 0 || originalEnd < 0) {

            return null;
        }

        return new KeyMatch(originalStart, originalEnd, key);
    }

    /*
     * ============================================================ FIND NEXT KEY
     * ============================================================
     */

    private KeyMatch findNextKey(String text, List<String> allKeys) {

        if (text == null || text.isBlank() || allKeys == null || allKeys.isEmpty()) {

            return null;
        }

        KeyMatch nearest = null;

        for (String key : allKeys) {

            if (key == null || key.isBlank()) {

                continue;
            }

            KeyMatch match = findKeyMatch(text, key);

            if (match == null) {
                continue;
            }

            if (nearest == null || match.getStartIndex() < nearest.getStartIndex()) {

                nearest = match;
            }
        }

        return nearest;
    }

    /*
     * ============================================================ FIND ALL KEY
     * POSITIONS ============================================================
     */

    private List<KeyPosition> findAllKeyPositions(String text, List<String> keys) {

        List<KeyPosition> positions = new ArrayList<>();

        if (text == null || text.isBlank() || keys == null || keys.isEmpty()) {

            return positions;
        }

        for (String key : keys) {

            if (key == null || key.isBlank()) {

                continue;
            }

            String normalizedText = normalizeForKeyMatching(text);

            String normalizedKey = normalizeForKeyMatching(key);

            int searchFrom = 0;

            while (searchFrom < normalizedText.length()) {

                int normalizedIndex = normalizedText.indexOf(normalizedKey, searchFrom);

                if (normalizedIndex < 0) {

                    break;
                }

                int originalIndex = mapNormalizedIndexToOriginal(text, normalizedIndex);

                if (originalIndex >= 0) {

                    positions.add(new KeyPosition(originalIndex, key));
                }

                searchFrom = normalizedIndex + normalizedKey.length();
            }
        }

        return positions;
    }

    /*
     * ============================================================ REMOVE DUPLICATE
     * POSITIONS ============================================================
     */

    private List<KeyPosition> removeDuplicatePositions(List<KeyPosition> positions) {

        List<KeyPosition> result = new ArrayList<>();

        int previousIndex = -1;

        for (KeyPosition position : positions) {

            if (position.getIndex() == previousIndex) {

                continue;
            }

            result.add(position);

            previousIndex = position.getIndex();
        }

        return result;
    }

    /*
     * ============================================================ NEXT LINE
     * FALLBACK ============================================================
     */

    private void extractValuesFromNextLines(String[] lines, List<String> keys, Map<String, String> result) {

        if (lines == null || lines.length == 0) {

            return;
        }

        for (int i = 0; i < lines.length; i++) {

            String line = lines[i];

            if (line == null || line.isBlank()) {

                continue;
            }

            for (String key : keys) {

                if (result.containsKey(key)) {
                    continue;
                }

                KeyMatch keyMatch = findKeyMatch(line, key);

                if (keyMatch == null) {

                    continue;
                }

                String afterKey = line.substring(keyMatch.getEndIndex());

                afterKey = cleanValue(afterKey);

                String value = extractNumber(afterKey);

                if (value != null) {

                    result.put(key, value);

                    continue;
                }

                /*
                 * Search following lines.
                 */
                for (int j = i + 1; j < lines.length; j++) {

                    String nextLine = lines[j];

                    if (nextLine == null || nextLine.isBlank()) {

                        continue;
                    }

                    /*
                     * Don't take another key's value.
                     */
                    if (containsAnyKey(nextLine, keys)) {

                        break;
                    }

                    value = extractNumber(cleanValue(nextLine));

                    if (value != null) {

                        result.put(key, value);

                        //log.info("KEY [{}] VALUE [{}] FOUND ON NEXT LINE", key, value);

                        break;
                    }
                }
            }
        }
    }

    /*
     * ============================================================ CHECK ANY KEY
     * ============================================================
     */

    private boolean containsAnyKey(String line, List<String> keys) {

        if (line == null || line.isBlank()) {

            return false;
        }

        for (String key : keys) {

            if (findKeyMatch(line, key) != null) {

                return true;
            }
        }

        return false;
    }

    /*
     * ============================================================ EXTRACT NUMBER
     * ============================================================
     */

    private String extractNumber(String text) {

        if (text == null || text.isBlank()) {

            return null;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(text);

        if (matcher.find()) {

            return matcher.group();
        }

        return null;
    }

    /*
     * ============================================================ CLEAN VALUE
     * ============================================================
     */

    private String cleanValue(String value) {

        if (value == null) {

            return "";
        }

        String result = value.trim();

        result = result.replaceFirst("^[\\s:=\\-–—|]+", "").trim();

        result = removeLHFlag(result);

        result = result.replaceFirst("^[\\s:=\\-–—|]+", "").trim();

        return result;
    }

    /*
     * ============================================================ REMOVE H / L
     * FLAG ============================================================
     */

    private String removeLHFlag(String text) {

        if (text == null) {

            return "";
        }

        String result = text.trim();

        while (true) {

            String updated = result.replaceFirst("(?i)^[HL](?:\\s*[:=\\-–—|])?\\s*", "").trim();

            if (updated.equals(result)) {

                break;
            }

            result = updated;
        }

        return result;
    }

    /*
     * ============================================================ NORMALIZE KEY
     * ============================================================
     */

    private String normalizeForKeyMatching(String text) {

        if (text == null) {

            return "";
        }

        /*
         * These become equivalent:
         *
         * LYMPH% LYMPH % LYMPH-%
         *
         * RDW-CV RDW CV
         *
         * RDWCV
         */
        return text.toLowerCase().replaceAll("[\\s\\-_\\%]+", "");
    }

    /*
     * ============================================================ MAP NORMALIZED
     * START TO ORIGINAL
     * ============================================================
     */

    private int mapNormalizedIndexToOriginal(String originalText, int normalizedIndex) {

        int normalizedPosition = 0;

        for (int i = 0; i < originalText.length(); i++) {

            char c = originalText.charAt(i);

            if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '%') {

                continue;
            }

            if (normalizedPosition == normalizedIndex) {

                return i;
            }

            normalizedPosition++;
        }

        return -1;
    }

    /*
     * ============================================================ MAP NORMALIZED
     * END TO ORIGINAL ============================================================
     */

    private int mapNormalizedEndToOriginal(String originalText, int normalizedEnd) {

        int normalizedPosition = 0;

        for (int i = 0; i < originalText.length(); i++) {

            char c = originalText.charAt(i);

            if (Character.isWhitespace(c) || c == '-' || c == '_' || c == '%') {

                continue;
            }

            normalizedPosition++;

            if (normalizedPosition == normalizedEnd) {

                int end = i + 1;

                /*
                 * Include spaces / % / formatting immediately after the key.
                 */
                while (end < originalText.length()) {

                    char next = originalText.charAt(end);

                    if (Character.isWhitespace(next) || next == '-' || next == '_' || next == '%') {

                        end++;

                    } else {

                        break;
                    }
                }

                return end;
            }
        }

        return originalText.length();
    }

    /*
     * ============================================================ CLEAN KEY
     * ============================================================
     */

    private String cleanKey(String key) {

        if (key == null) {

            return "";
        }

        String cleaned = key.trim().replace("\"", "");

        if (cleaned.startsWith("[")) {

            cleaned = cleaned.substring(1);
        }

        if (cleaned.endsWith("]")) {

            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        return cleaned.trim();
    }

    /*
     * ============================================================ CLEAN KEYS
     * ============================================================
     */

    private List<String> cleanKeys(List<String> keys) {

        List<String> result = new ArrayList<>();

        for (String key : keys) {

            String cleaned = cleanKey(key);

            if (!cleaned.isEmpty() && !result.contains(cleaned)) {

                result.add(cleaned);
            }
        }

        return result;
    }

    /*
     * ============================================================ NORMALIZE OCR
     * TEXT ============================================================
     */

    private String normalizeOCRText(String text) {

        if (text == null) {

            return "";
        }

        return text.replace("\u00A0", " ").replace("\r\n", "\n").replace("\r", "\n").trim();
    }

    /*
     * ============================================================ KEY POSITION
     * ============================================================
     */

    private static class KeyPosition {

        private final int index;

        private final String key;

        private KeyPosition(int index, String key) {

            this.index = index;
            this.key = key;
        }

        private int getIndex() {

            return index;
        }

        @SuppressWarnings("unused")
        private String getKey() {

            return key;
        }
    }

    /*
     * ============================================================ KEY MATCH
     * ============================================================
     */

    private static class KeyMatch {

        private final int startIndex;

        private final int endIndex;

        private final String key;

        private KeyMatch(int startIndex, int endIndex, String key) {

            this.startIndex = startIndex;

            this.endIndex = endIndex;

            this.key = key;
        }

        private int getStartIndex() {

            return startIndex;
        }

        private int getEndIndex() {

            return endIndex;
        }

        @SuppressWarnings("unused")
        private String getKey() {

            return key;
        }
    }
}