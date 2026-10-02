package com.OCR.Reader.service.impl;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
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
	 * ============================================================ IMAGE
	 * CONFIGURATION ============================================================
	 */

	/**
	 * Maximum processed image size.
	 *
	 * 500 KB = 500 * 1024 bytes
	 */
	private static final long MAX_IMAGE_SIZE = 500L * 1024L;

	/**
	 * Maximum width/height used before OCR.
	 *
	 * Large mobile/scanner images can consume a lot of memory.
	 */
	private static final int MAX_IMAGE_WIDTH = 2500;

	private static final int MAX_IMAGE_HEIGHT = 2500;

	/**
	 * JPEG quality range.
	 */
	private static final float MAX_JPEG_QUALITY = 0.90f;

	private static final float MIN_JPEG_QUALITY = 0.35f;

	private static final float JPEG_QUALITY_STEP = 0.05f;

	/**
	 * Number pattern used for extracting values.
	 */
	private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)");

	@Override
	public OCRResult processImage(MultipartFile file, List<String> keys, int columnCount) {

		OCRResult result = new OCRResult();

		File tempFile = null;

		try {

			/*
			 * ======================================================== VALIDATION
			 * ========================================================
			 */

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

			/*
			 * ======================================================== CREATE PROCESSED
			 * TEMP IMAGE
			 *
			 * Every request gets its own unique temporary file.
			 *
			 * Therefore multiple requests can run simultaneously.
			 * ========================================================
			 */

			tempFile = File.createTempFile("ocr_processed_", ".jpg");

			/*
			 * ======================================================== RESIZE + COMPRESS
			 * IMAGE
			 *
			 * Original uploaded image is NOT modified.
			 *
			 * OCR uses only the processed image.
			 * ========================================================
			 */

			processAndSaveImage(file, tempFile);

			log.debug("Processed OCR image: {} KB, dimensions: {}x{}", tempFile.length() / 1024,
					getImageWidth(tempFile), getImageHeight(tempFile));

			/*
			 * ======================================================== OCR
			 *
			 * New OCRProcessor per request.
			 *
			 * No synchronization / locking is used.
			 *
			 * Multiple requests can execute simultaneously.
			 * ========================================================
			 */

			OCRProcessor ocrProcessor = new OCRProcessor();

			String extractedText = ocrProcessor.extractTextFromImage(tempFile);

			/*
			 * ======================================================== EXTRACT KEY VALUES
			 * ========================================================
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
			 * ======================================================== DELETE PROCESSED
			 * IMAGE
			 *
			 * This executes whether OCR succeeds or fails.
			 * ========================================================
			 */

			deleteTempFile(tempFile);
		}

		return result;
	}

	/*
	 * ============================================================ IMAGE PROCESSING
	 * ============================================================
	 */

	/**
	 * Reads the uploaded image, adjusts its dimensions and compresses it to a
	 * maximum of approximately 500 KB.
	 *
	 * The original MultipartFile is never modified.
	 */
	private void processAndSaveImage(MultipartFile multipartFile, File outputFile) throws IOException {

		BufferedImage originalImage;

		/*
		 * ------------------------------------------------------------ Read uploaded
		 * image ------------------------------------------------------------
		 */

		try (InputStream inputStream = multipartFile.getInputStream()) {

			originalImage = ImageIO.read(inputStream);
		}

		if (originalImage == null) {

			throw new IOException("Unable to read uploaded image");
		}

		int originalWidth = originalImage.getWidth();

		int originalHeight = originalImage.getHeight();

		log.debug("Original image dimensions: {}x{}", originalWidth, originalHeight);

		/*
		 * ------------------------------------------------------------ Calculate
		 * resized dimensions.
		 *
		 * Aspect ratio is preserved.
		 * ------------------------------------------------------------
		 */

		Dimension dimension = calculateDimensions(originalWidth, originalHeight);

		BufferedImage resizedImage = resizeImage(originalImage, dimension.width, dimension.height);

		/*
		 * ------------------------------------------------------------ Compress to <=
		 * 500 KB. ------------------------------------------------------------
		 */

		compressToTargetSize(resizedImage, outputFile);

		/*
		 * ------------------------------------------------------------ Safety check
		 * ------------------------------------------------------------
		 */

		if (outputFile.length() > MAX_IMAGE_SIZE) {

			log.warn("Processed image is still larger than 500 KB: {} KB", outputFile.length() / 1024);
		} else {

			log.debug("Processed image size: {} KB", outputFile.length() / 1024);
		}

		/*
		 * Release image resources.
		 */
		originalImage.flush();

		resizedImage.flush();
	}

	/*
	 * ============================================================ CALCULATE
	 * DIMENSIONS ============================================================
	 */

	/**
	 * Keeps the original aspect ratio.
	 *
	 * Example:
	 *
	 * 4000 x 3000
	 *
	 * becomes approximately:
	 *
	 * 2500 x 1875
	 */
	private Dimension calculateDimensions(int originalWidth, int originalHeight) {

		if (originalWidth <= MAX_IMAGE_WIDTH && originalHeight <= MAX_IMAGE_HEIGHT) {

			return new Dimension(originalWidth, originalHeight);
		}

		double widthRatio = (double) MAX_IMAGE_WIDTH / originalWidth;

		double heightRatio = (double) MAX_IMAGE_HEIGHT / originalHeight;

		double ratio = Math.min(widthRatio, heightRatio);

		int newWidth = Math.max(1, (int) Math.round(originalWidth * ratio));

		int newHeight = Math.max(1, (int) Math.round(originalHeight * ratio));

		return new Dimension(newWidth, newHeight);
	}

	/*
	 * ============================================================ RESIZE IMAGE
	 * ============================================================
	 */

	private BufferedImage resizeImage(BufferedImage originalImage, int width, int height) {

		/*
		 * TYPE_INT_RGB is intentional because final OCR image is stored as JPEG.
		 *
		 * This also removes alpha/transparency.
		 */

		BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

		Graphics2D graphics = resizedImage.createGraphics();

		try {

			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			graphics.drawImage(originalImage, 0, 0, width, height, null);

		} finally {

			graphics.dispose();
		}

		return resizedImage;
	}

	/*
	 * ============================================================ COMPRESS IMAGE
	 * TO 500 KB ============================================================
	 */

	private void compressToTargetSize(BufferedImage image, File outputFile) throws IOException {

		/*
		 * Start with high quality.
		 */
		float quality = MAX_JPEG_QUALITY;

		byte[] compressedData = null;

		/*
		 * ------------------------------------------------------------ First try
		 * different JPEG qualities.
		 * ------------------------------------------------------------
		 */

		while (quality >= MIN_JPEG_QUALITY) {

			byte[] data = encodeJpeg(image, quality);

			if (data.length <= MAX_IMAGE_SIZE) {

				compressedData = data;

				log.debug("Image compressed successfully. Quality={}, Size={} KB", quality, data.length / 1024);

				break;
			}

			quality -= JPEG_QUALITY_STEP;
		}

		/*
		 * ------------------------------------------------------------ If quality alone
		 * was not enough, reduce dimensions.
		 * ------------------------------------------------------------
		 */

		if (compressedData == null) {

			int width = image.getWidth();

			int height = image.getHeight();

			BufferedImage currentImage = image;

			while (compressedData == null) {

				width = Math.max(800, (int) (width * 0.85));

				height = Math.max(800, (int) (height * 0.85));

				/*
				 * Prevent infinite loop.
				 */
				if (width == currentImage.getWidth() && height == currentImage.getHeight()) {

					break;
				}

				BufferedImage resized = resizeImage(currentImage, width, height);

				/*
				 * Try quality from high to low again.
				 */
				quality = MAX_JPEG_QUALITY;

				while (quality >= MIN_JPEG_QUALITY) {

					byte[] data = encodeJpeg(resized, quality);

					if (data.length <= MAX_IMAGE_SIZE) {

						compressedData = data;

						log.debug(
								"Image compressed after dimension reduction. "
										+ "Dimensions={}x{}, Quality={}, Size={} KB",
								width, height, quality, data.length / 1024);

						break;
					}

					quality -= JPEG_QUALITY_STEP;
				}

				/*
				 * Release previous intermediate image.
				 */
				if (currentImage != image) {

					currentImage.flush();
				}

				currentImage = resized;

				/*
				 * Final emergency condition.
				 */
				if (width <= 800 && height <= 800) {

					if (compressedData == null) {

						byte[] data = encodeJpeg(currentImage, MIN_JPEG_QUALITY);

						compressedData = data;
					}

					currentImage.flush();

					break;
				}
			}
		}

		/*
		 * ------------------------------------------------------------ Write final
		 * bytes to file. ------------------------------------------------------------
		 */

		if (compressedData == null) {

			throw new IOException("Unable to compress image to target size");
		}

		java.nio.file.Files.write(outputFile.toPath(), compressedData);

		/*
		 * ------------------------------------------------------------ Final size
		 * check. ------------------------------------------------------------
		 */

		if (outputFile.length() > MAX_IMAGE_SIZE) {

			log.warn("Unable to reduce image below 500 KB. Final size={} KB", outputFile.length() / 1024);
		}
	}

	/*
	 * ============================================================ JPEG ENCODING
	 * ============================================================
	 */

	private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {

		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

		ImageWriter writer = null;

		ImageOutputStream imageOutputStream = null;

		try {

			writer = ImageIO.getImageWritersByFormatName("jpg").next();

			imageOutputStream = ImageIO.createImageOutputStream(outputStream);

			writer.setOutput(imageOutputStream);

			ImageWriteParam writeParam = writer.getDefaultWriteParam();

			if (writeParam.canWriteCompressed()) {

				writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);

				writeParam.setCompressionQuality(quality);
			}

			writer.write(null, new IIOImage(image, null, null), writeParam);

			imageOutputStream.flush();

			return outputStream.toByteArray();

		} finally {

			if (imageOutputStream != null) {

				imageOutputStream.close();
			}

			if (writer != null) {

				writer.dispose();
			}

			outputStream.close();
		}
	}

	/*
	 * ============================================================ GET IMAGE WIDTH
	 * ============================================================
	 */

	private int getImageWidth(File file) {

		try {

			BufferedImage image = ImageIO.read(file);

			if (image == null) {

				return 0;
			}

			int width = image.getWidth();

			image.flush();

			return width;

		} catch (Exception e) {

			return 0;
		}
	}

	/*
	 * ============================================================ GET IMAGE HEIGHT
	 * ============================================================
	 */

	private int getImageHeight(File file) {

		try {

			BufferedImage image = ImageIO.read(file);

			if (image == null) {

				return 0;
			}

			int height = image.getHeight();

			image.flush();

			return height;

		} catch (Exception e) {

			return 0;
		}
	}

	/*
	 * ============================================================ DELETE TEMP FILE
	 * ============================================================
	 */

	private void deleteTempFile(File tempFile) {

		if (tempFile == null) {

			return;
		}

		try {

			if (tempFile.exists()) {

				java.nio.file.Files.deleteIfExists(tempFile.toPath());

				log.debug("Temporary OCR image deleted: {}", tempFile.getAbsolutePath());
			}

		} catch (Exception e) {

			log.warn("Unable to delete temporary OCR image: {}", tempFile.getAbsolutePath(), e);
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
		 * Every requested key searches all columns.
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
		 * Final fallback for values appearing on the following OCR line.
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
		 * Complete line fallback.
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

		if (columnCount <= 1) {

			columns.add(text);

			return columns;
		}

		/*
		 * First method: requested key positions.
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

			if (columns.size() == columnCount) {

				return columns;
			}
		}

		/*
		 * Second method: large whitespace.
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
		 * Third method: detected key positions.
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
		 * Last fallback: complete line.
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
		 * Find next requested key.
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

		String normalizedText = normalizeForKeyMatching(text);

		for (String key : keys) {

			if (key == null || key.isBlank()) {

				continue;
			}

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
				 * Include formatting immediately after key.
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
	 * ============================================================ DIMENSION CLASS
	 * ============================================================
	 */

	private static class Dimension {

		private final int width;

		private final int height;

		private Dimension(int width, int height) {

			this.width = width;

			this.height = height;
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