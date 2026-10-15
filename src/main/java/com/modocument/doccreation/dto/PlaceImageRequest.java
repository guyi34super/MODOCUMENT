package com.modocument.doccreation.dto;

import jakarta.validation.constraints.Size;

/**
 * JSON body of {@code PUT /api/doccreation/instances/{id}/images/{imageId}}:
 * the image region to place the photo in (drag and drop), or null to move it
 * back to the tray. A region that already holds a photo swaps the two.
 */
public record PlaceImageRequest(@Size(max = 64) String regionKey) {
}
