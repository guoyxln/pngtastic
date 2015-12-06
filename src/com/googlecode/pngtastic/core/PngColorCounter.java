package com.googlecode.pngtastic.core;

import com.googlecode.pngtastic.core.processing.PngCompressionHandler;
import com.googlecode.pngtastic.core.processing.PngFilterHandler;
import com.googlecode.pngtastic.core.processing.PngInterlaceHandler;
import com.googlecode.pngtastic.core.processing.PngtasticCompressionHandler;
import com.googlecode.pngtastic.core.processing.PngtasticFilterHandler;
import com.googlecode.pngtastic.core.processing.PngtasticInterlaceHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Counts the dominant colors in a png image
 *
 * @author rayvanderborght
 */
public class PngColorCounter {

	private final Logger log;

	private final PngFilterHandler pngFilterHandler;
	private final PngInterlaceHandler pngInterlaceHandler;
	private final PngCompressionHandler pngCompressionHandler;

	private final double distThreshold;
	private final double freqThreshold;
	private final int minAlpha;

	private Stats stats;
	public Stats getStats() { return stats; }

	public PngColorCounter() {
		this(Logger.NONE, 0.01D, 0.01D, 30);
	}

	public PngColorCounter(String logLevel, double distThreshold, double freqThreshold, int minAlpha) {
		this.log = new Logger(logLevel);

		this.distThreshold = distThreshold;
		this.freqThreshold = freqThreshold;
		this.minAlpha = minAlpha;

		this.pngFilterHandler = new PngtasticFilterHandler(log);
		this.pngInterlaceHandler = new PngtasticInterlaceHandler(log, pngFilterHandler);
		this.pngCompressionHandler = new PngtasticCompressionHandler(log);
	}

	/** */
	public void count(PngImage image) throws IOException {
		log.debug("=== COUNTING ===");

		// FIXME: support low bit depth interlaced images
		if (image.getInterlace() == 1 && image.getSampleBitCount() < 8) {
			log.debug("not supported");
			return;
		}
		final PngImage result = new PngImage(log);
		result.setInterlace((short) 0);

		final Iterator<PngChunk> itChunks = image.getChunks().iterator();
		final PngChunk chunk = processHeadChunks(result, false, itChunks);

		// collect image data chunks
		final byte[] inflatedImageData = getInflatedImageData(chunk, itChunks);

		final long width = image.getWidth();
		final long height = image.getHeight();
		final int scanlineLength = (int) (Math.ceil(width * image.getSampleBitCount() / 8F)) + 1;

		final List<byte[]> originalScanlines = (image.getInterlace() == 1)
				? pngInterlaceHandler.deInterlace((int) width, (int) height, image.getSampleBitCount(), inflatedImageData)
				: getScanlines(inflatedImageData, image.getSampleBitCount(), scanlineLength, height);

		final List<PngPixel> colors = getColors(image, originalScanlines);
		final List<PngPixel> results = getMergedColors(image, colors);

		stats = new Stats(image.getFileName(), width, height, colors.size(), results.size(), results.toString());
	}

	/* */
	private List<byte[]> getScanlines(byte[] inflatedImageData, int sampleBitCount, int rowLength, long height) {
		List<byte[]> rows = new ArrayList<>(Math.max((int) height, 0));
		byte[] previousRow = new byte[rowLength];

		for (int i = 0; i < height; i++) {
			int offset = i * rowLength;
			byte[] row = new byte[rowLength];
			System.arraycopy(inflatedImageData, offset, row, 0, rowLength);
			try {
				pngFilterHandler.deFilter(row, previousRow, sampleBitCount);
				rows.add(row);
				previousRow = row.clone();
			} catch (PngException e) {
				log.error("Error: %s", e.getMessage());
			}
		}
		return rows;
	}

	/* */
	private PngChunk processHeadChunks(PngImage result, boolean removeGamma, Iterator<PngChunk> itChunks) throws IOException {
		PngChunk chunk = null;
		while (itChunks.hasNext()) {
			chunk = itChunks.next();
			if (PngChunk.IMAGE_DATA.equals(chunk.getTypeString())) {
				break;
			}

			if (chunk.isRequired()) {
				if (removeGamma && PngChunk.IMAGE_GAMA.equalsIgnoreCase(chunk.getTypeString())) {
					continue;
				}
				ByteArrayOutputStream bytes = new ByteArrayOutputStream(chunk.getLength());
				DataOutputStream data = new DataOutputStream(bytes);

				data.write(chunk.getData());
				data.close();

				PngChunk newChunk = new PngChunk(chunk.getType(), bytes.toByteArray());
				if (PngChunk.IMAGE_HEADER.equals(chunk.getTypeString())) {
					newChunk.setInterlace((byte) 0);
				}
				result.addChunk(newChunk);
			}
		}
		return chunk;
	}

	/* */
	private byte[] getInflatedImageData(PngChunk chunk, Iterator<PngChunk> itChunks) throws IOException {
		ByteArrayOutputStream imageBytes = new ByteArrayOutputStream(chunk == null ? 0 : chunk.getLength());
		DataOutputStream imageData = new DataOutputStream(imageBytes);
		while (chunk != null) {
			if (PngChunk.IMAGE_DATA.equals(chunk.getTypeString())) {
				imageData.write(chunk.getData());
			} else {
				break;
			}
			chunk = itChunks.hasNext() ? itChunks.next() : null;
		}
		imageData.close();

		return pngCompressionHandler.inflate(imageBytes);
	}

	/* */
	private List<PngPixel> getColors(PngImage original, List<byte[]> rows) throws IOException {
		Map<PngPixel, Integer> colors = new LinkedHashMap<>();
		PngImageType imageType = PngImageType.forColorType(original.getColorType());
		int sampleSize = original.getSampleBitCount();

		int y = 0;
		for (byte[] row : rows) {
			final int sampleCount = ((row.length - 1) * 8) / sampleSize;
			final ByteArrayInputStream ins = new ByteArrayInputStream(row);
			final DataInputStream dis = new DataInputStream(ins);
			dis.readUnsignedByte();	// the filter byte

			for (int x = 0; x < sampleCount; x++) {
				switch (imageType) {
					case INDEXED_COLOR:
						// TODO: read pixels from palette
						break;

					case GREYSCALE:
					case GREYSCALE_ALPHA:
						// TODO: who knows
						break;

					case TRUECOLOR: {
						PngPixel pixel;
						if (original.getBitDepth() == 8) {
							final int r = dis.readUnsignedByte();
							final int g = dis.readUnsignedByte();
							final int b = dis.readUnsignedByte();
							pixel = new PngPixel(x, y, r, g, b, true);
						} else {
							final int r = dis.readUnsignedShort();
							final int g = dis.readUnsignedShort();
							final int b = dis.readUnsignedShort();
							pixel = new PngPixel(x, y, r, g, b, false);
						}
						if (pixel.getAlpha() > minAlpha) {
							final Integer count = colors.get(pixel);
							colors.put(pixel, (count == null) ? 1 : count + 1);
						}
						break;
					}

					case TRUECOLOR_ALPHA: {
						PngPixel pixel;
						if (original.getBitDepth() == 8) {
							final int r = dis.readUnsignedByte();
							final int g = dis.readUnsignedByte();
							final int b = dis.readUnsignedByte();
							final int a = dis.readUnsignedByte();
							pixel = new PngPixel(x, y, r, g, b, a);
						} else {
							final int r = dis.readUnsignedShort();
							final int g = dis.readUnsignedShort();
							final int b = dis.readUnsignedShort();
							final int a = dis.readUnsignedShort();
							pixel = new PngPixel(x, y, r, g, b, a);
						}
						if (pixel.getAlpha() > minAlpha) {
							final Integer count = colors.get(pixel);
							colors.put(pixel, (count == null) ? 1 : (count + 1));
						}
						break;
					}

					default:
						throw new IllegalArgumentException();
				}
			}
			y++;
		}
		log.debug("Full color count=%d", colors.size());

		if (freqThreshold > 0) {
			int minFreq = (int) (original.getWidth() * original.getHeight() * freqThreshold);
			for (Iterator<Map.Entry<PngPixel, Integer>> it = colors.entrySet().iterator(); it.hasNext(); ) {
				final Entry<PngPixel, Integer> entry = it.next();
				if (entry.getValue() < minFreq) {
					it.remove();
				}
			}
		}
		log.debug("Filtered color count=%d", colors.size());

		final List<PngPixel> results = new ArrayList<>(colors.keySet());
		for (PngPixel pixel : results) {
			final Integer freq = colors.get(pixel);
			pixel.setFreq(freq);
		}

		return results;
	}

	private List<PngPixel> getMergedColors(PngImage image, List<PngPixel> colors) {
		final int bits = image.getBitDepth();
		final List<PngPixel> copy = new ArrayList<>(colors);

		for (PngPixel pa : colors) {
			if (!pa.isDuplicate()) {
				for (Iterator<PngPixel> it = copy.iterator(); it.hasNext();) {
					final PngPixel pb = it.next();

					if (pb.isDuplicate()) {
						it.remove();

					} else if (pa != pb && pa.rgbaDistance(pb, bits) < distThreshold) {
						if (pa.getFreq() > pb.getFreq()) {
							pb.setDuplicate(true);
							it.remove();
						} else {
							pa.setDuplicate(true);
						}
					}
				}
			}
		}

		final List<PngPixel> results = new ArrayList<>();
		for (PngPixel p : colors) {
			if (!p.isDuplicate()) {
				results.add(p);
			}
		}

		return results;
	}

	/**
	 * Holds image processing info
	 */
	public static class Stats {
		private final String fileName;
		private final long width;
		private final long height;
		private final int totalColors;
		private final int dominantColors;
		private final String colors;

		public Stats(String fileName, long width, long height, int totalColors, int dominantColors, String colors) {
			this.fileName = fileName;
			this.width = width;
			this.height = height;
			this.totalColors = totalColors;
			this.dominantColors = dominantColors;
			this.colors = colors;
		}

		@Override
		public String toString() {
			return "Filename: " + fileName + " " + width + "x" + height
					+ "\nCandidates: " + totalColors
					+ "\nDominant Colors: " + dominantColors
					+ "\nColors: " + colors
					+ "\n";
		}
	}
}