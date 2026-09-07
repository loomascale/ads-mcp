package com.loomascale.mcp.security;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// How wide and tall an image is, without decoding it.
//
// Google's Performance Max slots each require a minimum size and an aspect ratio, so the
// bytes have to be measured before they are uploaded — an Asset that Google rejects on shape
// cannot be deleted through the API afterwards.
//
// Deliberately header-only. SafeImageFetcher's comment explains why it does not call
// ImageIO.read() on attacker-controlled input: decoding invites a decompression bomb, where
// a few kilobytes of PNG expand into gigabytes of raster. That reasoning still applies here,
// so this reads the PNG IHDR / JPEG SOF header through ImageReader.getWidth(0) and
// getHeight(0), which allocate nothing proportional to the pixel count. A 50000x50000 PNG
// reports its size and never becomes a BufferedImage.
//
// Separate @Component rather than a static helper so the tools' unit tests can stub it
// instead of carrying real image bytes.
@Slf4j
public class ImageDimensionReader {

  // A crafted header can claim any size. Beyond this the claim is not plausible for an ad
  // creative, and refusing it stops a nonsense value being passed off downstream as a
  // legitimate aspect ratio.
  private static final int MAX_PLAUSIBLE_PIXELS = 20_000;

  public Optional<ImageDimensions> read(byte[] imageBytes) {
    if (imageBytes == null || imageBytes.length == 0) {
      return Optional.empty();
    }
    try (ImageInputStream stream =
        ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
      if (stream == null) {
        return Optional.empty();
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        log.debug("No image reader recognised {} bytes", imageBytes.length);
        return Optional.empty();
      }
      ImageReader reader = readers.next();
      try {
        // seekForwardOnly and ignoreMetadata: the header is all that is wanted.
        reader.setInput(stream, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0
            || height <= 0
            || width > MAX_PLAUSIBLE_PIXELS
            || height > MAX_PLAUSIBLE_PIXELS) {
          log.debug("Implausible image header: {}x{}", width, height);
          return Optional.empty();
        }
        return Optional.of(new ImageDimensions(width, height));
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException e) {
      // A truncated or malformed header is a refusal, not a crash.
      log.debug("Could not read image dimensions: {}", e.getMessage());
      return Optional.empty();
    }
  }
}
