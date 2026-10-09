
import os
import json
import re
import time
import logging
import asyncio
from io import BytesIO
from threading import Lock

from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from dotenv import load_dotenv
from PIL import Image, ImageOps, UnidentifiedImageError

from google import genai
from google.genai import types


# ============================================================
# Configuration
# ============================================================

load_dotenv()

GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")

if not GEMINI_API_KEY:
    raise RuntimeError(
        "GEMINI_API_KEY is not configured. Add it to your .env file."
    )

MODEL_NAME = "gemini-3.8-flash"
THINKING_LEVEL = os.getenv("GEMINI_THINKING_LEVEL", "low")
MAX_OUTPUT_TOKENS = int(os.getenv("GEMINI_MAX_OUTPUT_TOKENS", "4096"))

MAX_IMAGE_DIMENSION = int(os.getenv("MAX_IMAGE_DIMENSION", "1600"))
JPEG_QUALITY = int(os.getenv("JPEG_QUALITY", "90"))
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_MB", "15")) * 1024 * 1024

INPUT_PRICE_PER_MILLION = float(
    os.getenv("GEMINI_INPUT_PRICE_PER_MILLION", "0.75")
)
OUTPUT_PRICE_PER_MILLION = float(
    os.getenv("GEMINI_OUTPUT_PRICE_PER_MILLION", "3.75")
)
USD_TO_INR = float(os.getenv("USD_TO_INR", "88.0"))

# Maximum OCR attempts allowed during this server process.
MAX_API_CALLS = 50

# Counts admitted OCR requests, including requests that later fail.
request_count = 0
request_count_lock = Lock()

# Cumulative token and cost estimates.
usage_lock = Lock()
usage_totals = {
    "api_calls": 0,
    "input_tokens": 0,
    "output_tokens": 0,
    "thinking_tokens": 0,
    "total_tokens": 0,
    "estimated_cost_usd": 0.0,
    "estimated_cost_inr": 0.0,
    "total_processing_seconds": 0.0,
}

client = genai.Client(api_key=GEMINI_API_KEY)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(message)s"
)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="Gemini Handwritten OCR API",
    version="2.1.0"
)


# ============================================================
# OCR Prompt
# ============================================================

OCR_PROMPT = """
You are a highly accurate OCR system for printed and handwritten documents.

Read the entire supplied image and extract all readable text.
Do not summarize the document.

Requirements:
1. Preserve visible spelling, numbers, punctuation and meaning.
2. Do not invent missing information or guess unclear handwriting.
3. Use "[unclear]" only when text is present but cannot be read confidently.
4. Preserve reading order, sections, tables and column relationships.
5. Preserve table headers and the relationship between each row's values.
6. Carefully distinguish similar characters:
   0/O, 1/I/l, 2/Z, 5/S, 6/G, 8/B and 9/g.
7. Preserve decimal points, percentages, signs, units, ranges and dates.
8. Include readable headers, footers, notes and patient details.
9. Mark text as printed, handwritten, mixed or unknown where possible.
10. Return one valid JSON object only. Do not use Markdown fences.

Return this JSON structure:
{
  "status": "SUCCESS",
  "data": {
    "document_type": "",
    "sections": [
      {
        "section_name": "",
        "content": []
      }
    ],
    "tables": [
      {
        "table_name": "",
        "headers": [],
        "rows": []
      }
    ],
    "lines": [
      {
        "text": "",
        "type": "printed"
      }
    ],
    "full_text": "",
    "signature_present": false
  }
}

If there is no readable text, return status "FAILED" and empty sections,
tables, lines and full_text, with signature_present false.
"""


# ============================================================
# Request limit helpers
# ============================================================

def reserve_request_slot() -> int:
    """
    Atomically reserve one of the 50 OCR request slots.
    The slot is consumed even if processing later fails.
    """

    global request_count

    with request_count_lock:
        if request_count >= MAX_API_CALLS:
            raise HTTPException(
                status_code=429,
                detail=(
                    "OCR request limit reached. Maximum 50 requests "
                    "are allowed during this server process."
                )
            )

        request_count += 1
        current_count = request_count

    logger.info(
        "OCR request slot reserved: %s/%s",
        current_count,
        MAX_API_CALLS
    )

    return current_count


@app.get("/api/limit")
async def get_request_limit():
    """View the current process-local OCR request limit."""

    with request_count_lock:
        used = request_count

    return {
        "status": "SUCCESS",
        "max_requests": MAX_API_CALLS,
        "requests_used": used,
        "requests_remaining": max(0, MAX_API_CALLS - used),
        "note": (
            "Counter resets when the server process restarts. "
            "This is not a cross-worker or permanent limit."
        )
    }


# ============================================================
# Image validation and preprocessing
# ============================================================

def prepare_image(image_bytes: bytes) -> dict:
    try:
        with Image.open(BytesIO(image_bytes)) as source:
            source_width, source_height = source.size
            source_format = source.format or "UNKNOWN"

            image = ImageOps.exif_transpose(source)
            image.load()
            image = image.convert("RGB")

    except (UnidentifiedImageError, OSError, ValueError) as exc:
        logger.warning("Invalid image upload: %s", exc)
        raise HTTPException(
            status_code=400,
            detail="Uploaded file is not a valid or supported image."
        )

    if MAX_IMAGE_DIMENSION > 0:
        longest_side = max(image.size)

        if longest_side > MAX_IMAGE_DIMENSION:
            scale = MAX_IMAGE_DIMENSION / longest_side
            new_size = (
                max(1, round(image.width * scale)),
                max(1, round(image.height * scale)),
            )
            image = image.resize(new_size, Image.Resampling.LANCZOS)

    output = BytesIO()
    image.save(
        output,
        format="JPEG",
        quality=JPEG_QUALITY,
        optimize=True
    )

    processed_bytes = output.getvalue()

    logger.info(
        "Image prepared: format=%s original=%sx%s sent=%sx%s "
        "original_bytes=%s processed_bytes=%s",
        source_format,
        source_width,
        source_height,
        image.width,
        image.height,
        len(image_bytes),
        len(processed_bytes)
    )

    return {
        "bytes": processed_bytes,
        "source_format": source_format,
        "original_width": source_width,
        "original_height": source_height,
        "width": image.width,
        "height": image.height,
    }


# ============================================================
# JSON parsing and normalization
# ============================================================

def extract_json(response_text: str) -> dict:
    if not response_text or not response_text.strip():
        raise ValueError("Gemini returned an empty response.")

    text = response_text.strip()
    text = re.sub(
        r"^```(?:json)?\s*",
        "",
        text,
        flags=re.IGNORECASE
    )
    text = re.sub(r"\s*```$", "", text).strip()

    try:
        result = json.loads(text)
    except json.JSONDecodeError:
        start = text.find("{")
        end = text.rfind("}")

        if start < 0 or end <= start:
            raise ValueError("Gemini response does not contain a JSON object.")

        result = json.loads(text[start:end + 1])

    if not isinstance(result, dict):
        raise ValueError("Gemini JSON response must be an object.")

    return result


def normalize_result(result: dict) -> dict:
    data = result.get("data", {})

    if not isinstance(data, dict):
        data = {}

    status = result.get("status", "SUCCESS")
    if status not in ("SUCCESS", "FAILED"):
        status = "SUCCESS"

    sections = data.get("sections", [])
    tables = data.get("tables", [])
    lines = data.get("lines", [])
    full_text = data.get("full_text", "")
    signature_present = data.get("signature_present", False)

    return {
        "status": status,
        "data": {
            "document_type": data.get("document_type", ""),
            "sections": sections if isinstance(sections, list) else [],
            "tables": tables if isinstance(tables, list) else [],
            "lines": lines if isinstance(lines, list) else [],
            "full_text": full_text if isinstance(full_text, str) else "",
            "signature_present": (
                signature_present
                if isinstance(signature_present, bool)
                else False
            ),
        }
    }


# ============================================================
# Token usage and cost logging
# ============================================================

def log_gemini_usage(response, elapsed_seconds: float) -> dict:
    usage = getattr(response, "usage_metadata", None)

    if usage is None:
        logger.warning("Gemini returned no usage metadata.")
        return {
            "available": False,
            "processing_seconds": round(elapsed_seconds, 3),
        }

    input_tokens = int(getattr(usage, "prompt_token_count", 0) or 0)
    output_tokens = int(getattr(usage, "candidates_token_count", 0) or 0)
    thinking_tokens = int(getattr(usage, "thoughts_token_count", 0) or 0)

    metadata_total = getattr(usage, "total_token_count", None)
    total_tokens = int(
        metadata_total
        if metadata_total is not None
        else input_tokens + output_tokens + thinking_tokens
    )

    # Estimated cost: thinking tokens are charged at the output-token rate.
    billable_output_tokens = output_tokens + thinking_tokens

    input_cost_usd = (
        input_tokens / 1_000_000
    ) * INPUT_PRICE_PER_MILLION

    output_cost_usd = (
        billable_output_tokens / 1_000_000
    ) * OUTPUT_PRICE_PER_MILLION

    cost_usd = input_cost_usd + output_cost_usd
    cost_inr = cost_usd * USD_TO_INR

    with usage_lock:
        usage_totals["api_calls"] += 1
        usage_totals["input_tokens"] += input_tokens
        usage_totals["output_tokens"] += output_tokens
        usage_totals["thinking_tokens"] += thinking_tokens
        usage_totals["total_tokens"] += total_tokens
        usage_totals["estimated_cost_usd"] += cost_usd
        usage_totals["estimated_cost_inr"] += cost_inr
        usage_totals["total_processing_seconds"] += elapsed_seconds
        snapshot = dict(usage_totals)

    print("\n" + "=" * 68)
    print("GEMINI TOKEN USAGE AND ESTIMATED API COST")
    print("=" * 68)
    print(f"Model: {MODEL_NAME}")
    print(f"Input tokens: {input_tokens}")
    print(f"Output tokens: {output_tokens}")
    print(f"Thinking tokens: {thinking_tokens}")
    print(f"Estimated billable output tokens: {billable_output_tokens}")
    print(f"Total tokens (API metadata): {total_tokens}")
    print("-" * 68)
    print(f"Input cost:       ${input_cost_usd:.8f}")
    print(f"Output cost:      ${output_cost_usd:.8f}")
    print(f"Estimated cost:   ${cost_usd:.8f}")
    print(f"Estimated cost:   Rs. {cost_inr:.6f}")
    print(f"Processing time:  {elapsed_seconds:.3f} seconds")
    print("-" * 68)
    print("CUMULATIVE TOTAL - CURRENT PROCESS")
    print(f"API calls with usage: {snapshot['api_calls']}")
    print(f"Input tokens: {snapshot['input_tokens']}")
    print(f"Output tokens: {snapshot['output_tokens']}")
    print(f"Thinking tokens: {snapshot['thinking_tokens']}")
    print(f"Total tokens: {snapshot['total_tokens']}")
    print(f"Estimated cost (USD): ${snapshot['estimated_cost_usd']:.8f}")
    print(f"Estimated cost (INR): Rs. {snapshot['estimated_cost_inr']:.6f}")
    print("=" * 68 + "\n")

    return {
        "available": True,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "thinking_tokens": thinking_tokens,
        "billable_output_tokens_estimated": billable_output_tokens,
        "total_tokens": total_tokens,
        "estimated_cost_usd": round(cost_usd, 10),
        "estimated_cost_inr": round(cost_inr, 8),
        "processing_seconds": round(elapsed_seconds, 3),
    }


# ============================================================
# Health check
# ============================================================

@app.get("/")
async def health_check():
    return {
        "status": "UP",
        "service": "Gemini Handwritten OCR",
        "model": MODEL_NAME,
    }


# ============================================================
# Usage totals
# ============================================================

@app.get("/api/usage")
async def get_usage_totals():
    with usage_lock:
        snapshot = dict(usage_totals)

    return {
        "status": "SUCCESS",
        "note": (
            "In-memory estimates reset when the process restarts. "
            "They are not Google's billing records."
        ),
        "model": MODEL_NAME,
        "usage": snapshot,
    }


# ============================================================
# OCR endpoint
# ============================================================

@app.post("/api/ocr/handwriting")
async def handwriting_ocr(file: UploadFile = File(...)):
    request_start = time.perf_counter()

    # Reserve a slot atomically before any OCR work.
    # Every admitted attempt consumes one of the 50 slots.
    reserve_request_slot()

    try:
        if not file.content_type or not file.content_type.startswith("image/"):
            raise HTTPException(
                status_code=400,
                detail="Only image files are supported."
            )

        image_bytes = await file.read()

        if not image_bytes:
            raise HTTPException(
                status_code=400,
                detail="Uploaded image is empty."
            )

        if len(image_bytes) > MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413,
                detail=(
                    f"Image exceeds the {MAX_UPLOAD_BYTES // (1024 * 1024)} MB "
                    "upload limit."
                )
            )

        logger.info(
            "OCR request received: filename=%s bytes=%s type=%s",
            file.filename,
            len(image_bytes),
            file.content_type
        )

        image_info = await asyncio.to_thread(prepare_image, image_bytes)

        logger.info(
            "Calling Gemini: model=%s thinking=%s",
            MODEL_NAME,
            THINKING_LEVEL
        )

        response = await client.aio.models.generate_content(
            model=MODEL_NAME,
            contents=[
                OCR_PROMPT,
                types.Part.from_bytes(
                    data=image_info["bytes"],
                    mime_type="image/jpeg"
                )
            ],
            config=types.GenerateContentConfig(
                response_mime_type="application/json",
                max_output_tokens=MAX_OUTPUT_TOKENS,
                thinking_config=types.ThinkingConfig(
                    thinking_level=THINKING_LEVEL
                ),
            )
        )

        gemini_elapsed = time.perf_counter() - request_start
        request_usage = log_gemini_usage(response, gemini_elapsed)

        raw_text = response.text

        if not raw_text:
            return JSONResponse(
                status_code=502,
                content={
                    "status": "FAILED",
                    "message": (
                        "Gemini returned an empty response. Try a clearer image "
                        "or increase GEMINI_MAX_OUTPUT_TOKENS."
                    ),
                    "data": {},
                    "gemini_usage": request_usage,
                }
            )

        try:
            result = normalize_result(extract_json(raw_text))
        except (ValueError, json.JSONDecodeError) as exc:
            logger.error("JSON parsing failed: %s", exc)
            logger.error("Response preview: %s", raw_text[:2000])

            return JSONResponse(
                status_code=502,
                content={
                    "status": "FAILED",
                    "message": (
                        "Gemini returned invalid or incomplete JSON. "
                        "Check the response preview in server logs."
                    ),
                    "gemini_usage": request_usage,
                    "data": {},
                }
            )

        total_elapsed = time.perf_counter() - request_start

        result["data"]["image"] = {
            "filename": file.filename,
            "original_width": image_info["original_width"],
            "original_height": image_info["original_height"],
            "width_sent_to_gemini": image_info["width"],
            "height_sent_to_gemini": image_info["height"],
            "format": image_info["source_format"],
            "processed_bytes": len(image_info["bytes"]),
        }

        result["data"]["gemini_usage"] = request_usage
        result["data"]["processing_seconds"] = round(total_elapsed, 3)

        logger.info(
            "OCR completed: status=%s elapsed=%.3f seconds",
            result["status"],
            total_elapsed
        )

        return JSONResponse(status_code=200, content=result)

    except HTTPException:
        raise

    except Exception:
        logger.exception("OCR processing failed")

        return JSONResponse(
            status_code=500,
            content={
                "status": "FAILED",
                "message": "OCR processing failed. Check server logs.",
                "data": {},
            }
        )

    finally:
        await file.close()


# ============================================================
# Run application locally
# # ============================================================
#
# if __name__ == "__main__":
#     import uvicorn
#
#     uvicorn.run(
#         app,
#         host="0.0.0.0",
#         port=8080,
#         reload=False,
#         workers=1
#     )
