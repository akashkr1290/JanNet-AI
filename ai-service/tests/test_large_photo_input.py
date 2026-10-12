"""V34 location upgrade: the app now uploads photos up to 2048 px / 2 MB
(previously 1600 px). Preprocessing must still shrink to
preprocess_max_side_px before the costly denoise (audit GAP-009), so a
2048 px photo costs the same as before and AI analysis does not time out.
"""
import time

import cv2
import numpy as np

from app.config import get_settings
from app.services.preprocessing import preprocess_image
from app.services.yolo_service import MODEL_INPUT_SIZE


def _phone_like_jpeg(width: int, height: int, quality: int = 90) -> bytes:
    # gradient + shapes + mild noise: compresses like a real street photo
    y, x = np.mgrid[0:height, 0:width]
    image = np.dstack([(x * 255 // width), (y * 255 // height), ((x + y) * 255 // (width + height))]).astype(np.uint8)
    for i in range(0, width, 160):
        cv2.rectangle(image, (i, height // 3), (i + 80, height // 3 + 120), (40, 40, 200), -1)
    # sharp edges (road markings / bricks) so the intake blur check sees a focused photo
    image[((y // 12 + x // 12) % 2 == 0) & (y > height // 2)] = (230, 230, 230)
    noise = np.random.default_rng(7).integers(0, 12, image.shape, dtype=np.uint8)
    image = cv2.add(image, noise)
    ok, buf = cv2.imencode(".jpg", image, [cv2.IMWRITE_JPEG_QUALITY, quality])
    assert ok
    return buf.tobytes()


def test_2048px_photo_is_downscaled_before_denoise_and_stays_fast():
    data = _phone_like_jpeg(2048, 1536)
    assert len(data) <= 2 * 1024 * 1024  # the app's upload cap

    started = time.perf_counter()
    result = preprocess_image(data)
    elapsed = time.perf_counter() - started

    assert (result.width, result.height) == (2048, 1536)
    assert max(result.model_image.shape[:2]) == get_settings().preprocess_max_side_px
    assert result.normalized_image.shape[1::-1] == tuple(MODEL_INPUT_SIZE)
    assert elapsed < 10, f"preprocessing took {elapsed:.1f}s"
