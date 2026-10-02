package com.OCR.Reader.service.impl;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

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

	/*
	 * ============================================================ IMAGE LIMITS
	 * ============================================================
	 *
	 * Maximum image dimension:
	 *
	 * 768 x 768
	 *
	 * IMPORTANT: We do NOT force the image to exactly 768 x 768.
	 *
	 * Aspect ratio is preserved.
	 *
	 * Examples:
	 *
	 * 3024 x 4032 -> 576 x 768 4032 x 3024 -> 768 x 576 768 x 768 -> 768 x 768 500
	 * x 800 -> 480 x 768
	 */

	private static final int MAX_IMAGE_DIMENSION = 768;

	/*
	 * Maximum processed image size.
	 *
	 * This is used only for JPEG compression.
	 */
	private static final long MAX_IMAGE_SIZE = 500L * 1024L; // 500 KB

	/*
	 * JPEG compression settings.
	 */
	private static final float JPEG_QUALITY_START = 0.85f;
	private static final float JPEG_QUALITY_MIN = 0.55f;

	/*
	 * Number extraction pattern.
	 *
	 * Examples:
	 *
	 * 12 12.5 -12.5 +12.5 .5
	 */
	private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");

	/*
	 * ============================================================ MAIN OCR METHOD
	 * ============================================================
	 */

	@Override
	public OCRResult processImage(MultipartFile file, List<String> keys, int columnCount) {

		OCRResult result = new OCRResult();

		File tempFile = null;

		try {

			/*
			 * ---------------------------------------------------- VALIDATE IMAGE
			 * ----------------------------------------------------
			 */

			if (file == null || file.isEmpty()) {
				result.setStatus(AppConstants.ERROR);
				return result;
			}

			/*
			 * ---------------------------------------------------- VALIDATE KEYS
			 * ----------------------------------------------------
			 */

			if (keys == null || keys.isEmpty()) {
				result.setStatus(AppConstants.ERROR);
				return result;
			}

			/*
			 * ---------------------------------------------------- VALIDATE COLUMN COUNT
			 * ----------------------------------------------------
			 */

			if (columnCount < 1) {
				columnCount = 1;
			}

			/*
			 * ---------------------------------------------------- IMAGE PREPROCESSING
			 * ----------------------------------------------------
			 *
			 * MultipartFile -> dimension check -> proportional resize if required -> JPEG
			 * compression if required -> temporary file
			 */

			tempFile = preprocessImage(file);

			/*
			 * ---------------------------------------------------- OCR
			 * ----------------------------------------------------
			 *
			 * OCRProcessor is intentionally created per request.
			 *
			 * This keeps your existing concurrent-request behavior.
			 */

			OCRProcessor ocrProcessor = new OCRProcessor();

			String extractedText = ocrProcessor.extractTextFromImage(tempFile);

			/*
			 * ---------------------------------------------------- KEY / VALUE EXTRACTION
			 * ----------------------------------------------------
			 */

			Map<String, String> keyValues = extractKeyValues(extractedText, keys, columnCount);

			result.setData(keyValues);
			result.setStatus(AppConstants.SUCCESS);

		} catch (IOException e) {

			log.error("Error while processing OCR image", e);

			result.setStatus(AppConstants.ERROR);

		} catch (Exception e) {

			log.error("Unexpected OCR error", e);

			result.setStatus(AppConstants.ERROR);

		} finally {

			/*
			 * ---------------------------------------------------- ALWAYS DELETE TEMPORARY
			 * IMAGE ----------------------------------------------------
			 */

			deleteTempFile(tempFile);
		}

		return result;
	}

	/*
	 * ============================================================ IMAGE
	 * PREPROCESSING ============================================================
	 *
	 * Rules:
	 *
	 * 1. Dimension <= 768 AND size <= 500 KB -> use original image as-is
	 *
	 * 2. Dimension > 768 -> proportional resize
	 *
	 * 3. Size > 500 KB -> JPEG compression
	 *
	 * 4. Dimension > 768 AND size > 500 KB -> resize + compress
	 *
	 * 768 = PIXEL LIMIT
	 *
	 * 500 KB = FILE SIZE LIMIT
	 */

	private File preprocessImage(MultipartFile file) throws IOException {

		File tempFile = File.createTempFile("ocr_processed_", ".jpg");

		try {

			/*
			 * ---------------------------------------------------- STEP 1 READ ORIGINAL
			 * DIMENSIONS ----------------------------------------------------
			 *
			 * Do not fully decode image here.
			 */

			int originalWidth;
			int originalHeight;

			try (var inputStream = file.getInputStream();
					ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {

				if (imageInputStream == null) {
					throw new IOException("Unable to create image input stream");
				}

				var readers = ImageIO.getImageReaders(imageInputStream);

				if (!readers.hasNext()) {
					throw new IOException("Unsupported image format");
				}

				ImageReader reader = readers.next();

				try {

					reader.setInput(imageInputStream, true, true);

					originalWidth = reader.getWidth(0);
					originalHeight = reader.getHeight(0);

				} finally {

					reader.dispose();
				}
			}

			log.info("Original image: {}x{}, size={} KB", originalWidth, originalHeight, file.getSize() / 1024);

			/*
			 * ---------------------------------------------------- STEP 2 IMAGE ALREADY
			 * WITHIN LIMITS ----------------------------------------------------
			 *
			 * Keep original image.
			 */

			if (originalWidth <= MAX_IMAGE_DIMENSION && originalHeight <= MAX_IMAGE_DIMENSION
					&& file.getSize() <= MAX_IMAGE_SIZE) {

				file.transferTo(tempFile);

				log.info("Image accepted as-is: {}x{}, size={} KB", originalWidth, originalHeight,
						tempFile.length() / 1024);

				return tempFile;
			}

			/*
			 * ---------------------------------------------------- STEP 3 CALCULATE TARGET
			 * DIMENSIONS ----------------------------------------------------
			 *
			 * Maximum dimension = 768.
			 *
			 * Aspect ratio preserved.
			 */

			int targetWidth = originalWidth;
			int targetHeight = originalHeight;

			if (originalWidth > MAX_IMAGE_DIMENSION || originalHeight > MAX_IMAGE_DIMENSION) {

				double scale = Math.min((double) MAX_IMAGE_DIMENSION / originalWidth,
						(double) MAX_IMAGE_DIMENSION / originalHeight);

				targetWidth = Math.max(1, (int) Math.round(originalWidth * scale));

				targetHeight = Math.max(1, (int) Math.round(originalHeight * scale));
			}

			log.info("Target image dimensions: {}x{}", targetWidth, targetHeight);

			/*
			 * ---------------------------------------------------- STEP 4 IMAGEIO
			 * SUBSAMPLING ----------------------------------------------------
			 *
			 * Reduces memory usage for large camera images.
			 */

			int sampleX = Math.max(1, (int) Math.floor((double) originalWidth / targetWidth));

			int sampleY = Math.max(1, (int) Math.floor((double) originalHeight / targetHeight));

			int sample = Math.min(sampleX, sampleY);

			/*
			 * ---------------------------------------------------- STEP 5 READ IMAGE
			 * ----------------------------------------------------
			 */

			BufferedImage image;

			try (var inputStream = file.getInputStream();
					ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {

				if (imageInputStream == null) {
					throw new IOException("Unable to create image input stream");
				}

				var readers = ImageIO.getImageReaders(imageInputStream);

				if (!readers.hasNext()) {
					throw new IOException("Unsupported image format");
				}

				ImageReader reader = readers.next();

				try {

					reader.setInput(imageInputStream, true, true);

					javax.imageio.ImageReadParam param = reader.getDefaultReadParam();

					if (sample > 1) {
						param.setSourceSubsampling(sample, sample, 0, 0);
					}

					image = reader.read(0, param);

				} finally {

					reader.dispose();
				}
			}

			if (image == null) {
				throw new IOException("Unable to decode image");
			}

			/*
			 * ---------------------------------------------------- STEP 6 FINAL RESIZE
			 * ----------------------------------------------------
			 *
			 * Subsampling uses integer values.
			 *
			 * Therefore decoded image may still be larger than 768px.
			 *
			 * Resize only when actually required.
			 */

			if (image.getWidth() > MAX_IMAGE_DIMENSION || image.getHeight() > MAX_IMAGE_DIMENSION) {

				double scale = Math.min((double) MAX_IMAGE_DIMENSION / image.getWidth(),
						(double) MAX_IMAGE_DIMENSION / image.getHeight());

				int finalWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));

				int finalHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));

				BufferedImage resized = resizeImage(image, finalWidth, finalHeight);

				image.flush();
				image = resized;
			}

			/*
			 * ---------------------------------------------------- STEP 7 CONVERT TO RGB
			 * ----------------------------------------------------
			 *
			 * Handles PNG / transparency.
			 *
			 * White background is used.
			 */

			if (image.getType() != BufferedImage.TYPE_INT_RGB) {

				BufferedImage rgbImage = new BufferedImage(image.getWidth(), image.getHeight(),
						BufferedImage.TYPE_INT_RGB);

				Graphics2D graphics = rgbImage.createGraphics();

				try {

					graphics.setColor(Color.WHITE);

					graphics.fillRect(0, 0, image.getWidth(), image.getHeight());

					graphics.drawImage(image, 0, 0, null);

				} finally {

					graphics.dispose();
				}

				image.flush();
				image = rgbImage;
			}

			/*
			 * ---------------------------------------------------- STEP 8 WRITE JPEG
			 * ----------------------------------------------------
			 *
			 * Start quality = 0.85.
			 *
			 * If > 500 KB:
			 *
			 * 0.80 0.75 0.70 ...
			 *
			 * Minimum = 0.55
			 */

			writeCompressedJpeg(image, tempFile, JPEG_QUALITY_START);

			float quality = JPEG_QUALITY_START;

			while (tempFile.length() > MAX_IMAGE_SIZE && quality > JPEG_QUALITY_MIN) {

				quality -= 0.05f;

				if (quality < JPEG_QUALITY_MIN) {
					quality = JPEG_QUALITY_MIN;
				}

				writeCompressedJpeg(image, tempFile, quality);
			}

			/*
			 * ---------------------------------------------------- FINAL LOG
			 * ----------------------------------------------------
			 */

			log.info("Processed image: {}x{}, size={} KB, JPEG quality={}", image.getWidth(), image.getHeight(),
					tempFile.length() / 1024, quality);

			/*
			 * ---------------------------------------------------- RELEASE IMAGE MEMORY
			 * ----------------------------------------------------
			 */

			image.flush();

			return tempFile;

		} catch (Exception e) {

			deleteTempFile(tempFile);

			throw e;
		}
	}

	/*
	 * ============================================================ RESIZE IMAGE
	 * ============================================================
	 *
	 * Aspect ratio is calculated by caller.
	 */

	private BufferedImage resizeImage(BufferedImage source, int width, int height) {

		BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		Graphics2D graphics = resized.createGraphics();

		try {

			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			graphics.setColor(Color.WHITE);

			graphics.fillRect(0, 0, width, height);

			graphics.drawImage(source, 0, 0, width, height, null);

		} finally {

			graphics.dispose();
		}

		return resized;
	}

	/*
	 * ============================================================ WRITE COMPRESSED
	 * JPEG ============================================================
	 */

	private void writeCompressedJpeg(BufferedImage image, File outputFile, float quality) throws IOException {

		ImageWriter writer = null;

		try {

			var writers = ImageIO.getImageWritersByFormatName("jpg");

			if (!writers.hasNext()) {
				throw new IOException("JPEG writer not available");
			}

			writer = writers.next();

			try (var outputStream = new FileOutputStream(outputFile);

					ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputStream)) {

				writer.setOutput(imageOutputStream);

				ImageWriteParam param = writer.getDefaultWriteParam();

				if (param.canWriteCompressed()) {

					param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

					param.setCompressionQuality(quality);
				}

				writer.write(null, new IIOImage(image, null, null), param);

				imageOutputStream.flush();
			}

		} finally {

			if (writer != null) {
				writer.dispose();
			}
		}
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
		 * Every requested key searches ALL columns.
		 */

		for (String key : cleanedKeys) {

			if (result.containsKey(key)) {
				continue;
			}

			String value = findKeyInAllColumns(key, lines, cleanedKeys, columnCount);

			if (value != null && !value.isBlank()) {

				result.put(key, value);

			} else {

				log.warn("NOT FOUND -> {}", key);
			}
		}

		/*
		 * Final fallback: value may exist on next OCR line.
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

			List<String> columns = createColumns(line, allKeys, columnCount);

			log.debug("OCR LINE: [{}]", line);

			log.debug("COLUMNS: {}", columns);

			/*
			 * Search key in every column.
			 */

			for (int i = 0; i < columns.size(); i++) {

				String column = columns.get(i);

				if (column == null || column.isBlank()) {

					continue;
				}

				log.debug("Searching key [{}] in column {} -> [{}]", key, i + 1, column);

				String value = extractValueFromColumn(column, key, allKeys);

				if (value != null && !value.isBlank()) {

					return value;
				}
			}
		}

		/*
		 * -------------------------------------------------------- COMPLETE LINE
		 * FALLBACK --------------------------------------------------------
		 */

		for (String line : lines) {

			if (line == null || line.isBlank()) {

				continue;
			}

			String value = extractValueFromColumn(line, key, allKeys);

			if (value != null && !value.isBlank()) {

				return value;
			}
		}

		return null;
	}

	/*
	 * ============================================================ CREATE COLUMNS
	 * ============================================================
	 */

	private List<String> createColumns(String line, List<String> keys, int columnCount) {

		List<String> columns = new ArrayList<>();

		if (line == null || line.isBlank()) {

			return columns;
		}

		String text = line.trim();

		/*
		 * Single column.
		 */

		if (columnCount <= 1) {

			columns.add(text);

			return columns;
		}

		/*
		 * -------------------------------------------------------- FIRST METHOD
		 *
		 * Find requested keys and create column from one key until the next key.
		 * --------------------------------------------------------
		 */

		List<KeyPosition> positions = findAllKeyPositions(text, keys);

		positions.sort(Comparator.comparingInt(KeyPosition::getIndex));

		List<KeyPosition> uniquePositions = removeDuplicatePositions(positions);

		if (!uniquePositions.isEmpty()) {

			addColumnsFromKeyPositions(text, uniquePositions, columns);

			if (columns.size() == columnCount) {
				return columns;
			}
		}

		/*
		 * -------------------------------------------------------- SECOND METHOD
		 *
		 * OCR normally creates larger spaces between columns.
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
		 * Use detected key positions again.
		 * --------------------------------------------------------
		 */

		if (!uniquePositions.isEmpty()) {

			columns.clear();

			addColumnsFromKeyPositions(text, uniquePositions, columns);

			return columns;
		}

		/*
		 * -------------------------------------------------------- LAST FALLBACK
		 * --------------------------------------------------------
		 */

		columns.clear();

		columns.add(text);

		return columns;
	}

	/*
	 * ============================================================ ADD COLUMNS
	 * USING KEY POSITIONS
	 * ============================================================
	 */

	private void addColumnsFromKeyPositions(String text, List<KeyPosition> positions, List<String> columns) {

		for (int i = 0; i < positions.size(); i++) {

			int start = positions.get(i).getIndex();

			int end = text.length();

			if (i + 1 < positions.size()) {

				end = positions.get(i + 1).getIndex();
			}

			if (start >= end) {
				continue;
			}

			String column = text.substring(start, end).trim();

			if (!column.isEmpty()) {
				columns.add(column);
			}
		}
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
		 * Find actual OCR key position.
		 */

		KeyMatch keyMatch = findKeyMatch(column, requestedKey);

		if (keyMatch == null) {
			return null;
		}

		int valueStart = keyMatch.getEndIndex();

		if (valueStart >= column.length()) {
			return null;
		}

		String valuePart = column.substring(valueStart);

		/*
		 * Stop before next requested key.
		 */

		KeyMatch nextKey = findNextKey(valuePart, allKeys);

		if (nextKey != null) {

			valuePart = valuePart.substring(0, nextKey.getStartIndex());
		}

		valuePart = cleanValue(valuePart);

		return extractNumber(valuePart);
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

		/*
		 * Normalize OCR text only once.
		 *
		 * This avoids recalculating the same normalized text for every requested key.
		 */

		String normalizedText = normalizeForKeyMatching(text);

		if (normalizedText.isEmpty()) {
			return positions;
		}

		for (String key : keys) {

			if (key == null || key.isBlank()) {

				continue;
			}

			String normalizedKey = normalizeForKeyMatching(key);

			if (normalizedKey.isEmpty()) {
				continue;
			}

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
					 * Do not take another key's value.
					 */

					if (containsAnyKey(nextLine, keys)) {

						break;
					}

					value = extractNumber(cleanValue(nextLine));

					if (value != null) {

						result.put(key, value);

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

		result = result.replaceFirst("^[\\s:=\\\\\\-–—|]+", "").trim();

		result = removeLHFlag(result);

		result = result.replaceFirst("^[\\s:=\\\\\\-–—|]+", "").trim();

		return result;
	}

	/*
	 * ============================================================ REMOVE H / L
	 * FLAG ============================================================
	 *
	 * Examples:
	 *
	 * H: 12.5 L: 10.2 H 12.5 L-12.5
	 */

	private String removeLHFlag(String text) {

		if (text == null) {
			return "";
		}

		String result = text.trim();

		while (true) {

			String updated = result.replaceFirst("(?i)^[HL]\\s*[:=\\\\-–—|]?\\s*", "").trim();

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
	 *
	 * These become equivalent:
	 *
	 * LYMPH% LYMPH % LYMPH-%
	 *
	 * RDW-CV RDW CV RDWCV
	 */

	private String normalizeForKeyMatching(String text) {

		if (text == null) {
			return "";
		}

		return text.toLowerCase().replaceAll("[\\s\\-_%]+", "");
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
				 * Include formatting characters immediately after the key.
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
	 * ============================================================ DELETE TEMP FILE
	 * ============================================================
	 */

	private void deleteTempFile(File tempFile) {

		if (tempFile == null || !tempFile.exists()) {

			return;
		}

		if (!tempFile.delete()) {

			log.warn("Unable to delete temp file: {}", tempFile.getAbsolutePath());
		}
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