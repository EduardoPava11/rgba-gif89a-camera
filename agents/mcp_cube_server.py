#!/usr/bin/env python3
"""MCP Server for Cube Testing

Provides tools for validating quantized cube data and testing
the 81×81×81 GIF cube vision with global palette verification.
"""

import asyncio
import json
import subprocess
from pathlib import Path
from typing import Dict, List, Any

from mcp import Server, Resource, Tool
from mcp.types import TextContent, ImageContent


server = Server("cube-testing-server")


@server.resource("/quantized_cube_data")
async def get_quantized_data() -> Resource:
    """Provide quantized cube data for testing"""
    result = subprocess.run(
        ["cargo", "run", "--bin", "generate_cube_data"],
        capture_output=True,
        text=True,
        cwd=Path(__file__).parent.parent / "rust-core"
    )
    return Resource(
        uri="/quantized_cube_data",
        name="Quantized Cube Data",
        content=TextContent(text=result.stdout)
    )


@server.tool("validate_palette")
async def validate_palette(data: dict) -> dict:
    """Validate global palette properties"""
    palette = data.get("global_palette", [])
    frames = data.get("indexed_frames", [])
    
    # Validate palette is shared
    max_index = len(palette) - 1
    for frame_idx, frame in enumerate(frames):
        for pixel_idx, index in enumerate(frame):
            if index > max_index:
                return {
                    "valid": False,
                    "error": f"Frame {frame_idx} pixel {pixel_idx} has invalid index {index}"
                }
    
    # Calculate usage
    usage = [0] * len(palette)
    for frame in frames:
        for index in frame:
            usage[index] += 1
    
    unused = sum(1 for u in usage if u == 0)
    utilization = (len(palette) - unused) / len(palette)
    
    return {
        "valid": True,
        "paletteSize": len(palette),
        "utilization": utilization,
        "unusedColors": unused
    }


@server.tool("measure_temporal_drift")
async def measure_temporal_drift(data: dict) -> dict:
    """Measure frame-to-frame palette drift"""
    frames = data.get("indexed_frames", [])
    
    drifts = []
    for i in range(1, len(frames)):
        prev_frame = frames[i-1]
        curr_frame = frames[i]
        
        # Count changed pixels
        changed = sum(1 for p, c in zip(prev_frame, curr_frame) if p != c)
        drift = changed / len(prev_frame)
        drifts.append(drift)
    
    return {
        "meanDrift": sum(drifts) / len(drifts) if drifts else 0,
        "maxDrift": max(drifts) if drifts else 0,
        "frameCount": len(frames),
        "drifts": drifts[:10]  # First 10 for inspection
    }


@server.tool("validate_cube_structure")
async def validate_cube_structure(cube_path: str) -> dict:
    """Validate a quantized cube JSON file"""
    try:
        with open(cube_path, 'r') as f:
            cube_data = json.load(f)
        
        # Structural validation
        errors = []
        warnings = []
        
        # Check frame count
        frame_count = len(cube_data.get("indexed_frames", []))
        if frame_count != 81:
            errors.append(f"Expected 81 frames, got {frame_count}")
        
        # Check frame dimensions
        for idx, frame in enumerate(cube_data.get("indexed_frames", [])):
            if len(frame) != 81 * 81:
                errors.append(f"Frame {idx} has {len(frame)} pixels, expected 6561")
        
        # Check palette size
        palette_size = len(cube_data.get("global_palette", []))
        if palette_size > 256:
            errors.append(f"Palette has {palette_size} colors, max is 256")
        
        # Check temporal metrics
        temporal = cube_data.get("temporal_metrics", {})
        stability = temporal.get("palette_stability", 0)
        if stability < 0.85:
            warnings.append(f"Low palette stability: {stability:.2f}")
        
        # Check quality metrics
        metadata = cube_data.get("metadata", {})
        mean_delta_e = metadata.get("mean_delta_e", 999)
        if mean_delta_e > 2.0:
            warnings.append(f"High mean ΔE: {mean_delta_e:.2f}")
        
        p95_delta_e = metadata.get("p95_delta_e", 999)
        if p95_delta_e > 5.0:
            warnings.append(f"High P95 ΔE: {p95_delta_e:.2f}")
        
        return {
            "valid": len(errors) == 0,
            "errors": errors,
            "warnings": warnings,
            "metrics": {
                "frameCount": frame_count,
                "paletteSize": palette_size,
                "stability": stability,
                "meanDeltaE": mean_delta_e,
                "p95DeltaE": p95_delta_e
            }
        }
    except Exception as e:
        return {
            "valid": False,
            "errors": [str(e)],
            "warnings": [],
            "metrics": {}
        }


@server.tool("run_cube_tests")
async def run_cube_tests() -> dict:
    """Run the Rust cube validation tests"""
    result = subprocess.run(
        ["cargo", "test", "-p", "m2-quant", "cube_tests", "--", "--nocapture"],
        capture_output=True,
        text=True,
        cwd=Path(__file__).parent.parent / "rust-core"
    )
    
    return {
        "success": result.returncode == 0,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "testsRun": result.stdout.count("test "),
        "testsPassed": result.stdout.count("ok"),
        "testsFailed": result.stdout.count("FAILED")
    }


@server.tool("generate_test_cube")
async def generate_test_cube(output_path: str) -> dict:
    """Generate a test cube with known properties"""
    # Generate synthetic test data
    cube_data = {
        "global_palette": [[i, i, i] for i in range(256)],  # Grayscale palette
        "indexed_frames": [],
        "attention_maps": [],
        "palette_usage": [],
        "temporal_metrics": {
            "palette_stability": 0.95,
            "frame_to_frame_drift": [0.02] * 80,
            "global_color_distribution": [1.0/256] * 256
        },
        "metadata": {
            "quantization_method": "test_synthetic",
            "color_space": "oklab",
            "dithering_enabled": False,
            "processing_time_ms": 100,
            "mean_delta_e": 1.5,
            "p95_delta_e": 3.0
        }
    }
    
    # Generate 81 frames with gradient pattern
    for frame_idx in range(81):
        frame = []
        usage_counts = [0] * 256
        
        for y in range(81):
            for x in range(81):
                # Create a shifting gradient pattern
                index = ((x + y + frame_idx) * 256 // 162) % 256
                frame.append(index)
                usage_counts[index] += 1
        
        cube_data["indexed_frames"].append(frame)
        
        # Calculate usage stats
        colors_used = sum(1 for c in usage_counts if c > 0)
        total_pixels = 81 * 81
        most_frequent = sorted(
            [(i, c/total_pixels) for i, c in enumerate(usage_counts) if c > 0],
            key=lambda x: x[1],
            reverse=True
        )[:5]
        
        cube_data["palette_usage"].append({
            "frame_index": frame_idx,
            "colors_used": colors_used,
            "most_frequent": most_frequent,
            "least_frequent": most_frequent[-5:] if len(most_frequent) > 5 else []
        })
        
        # Simple attention map (center-weighted)
        attention = []
        for y in range(81):
            for x in range(81):
                dist = ((x - 40) ** 2 + (y - 40) ** 2) ** 0.5
                attention.append(max(0, 1 - dist / 57))
        cube_data["attention_maps"].append(attention)
    
    # Write to file
    with open(output_path, 'w') as f:
        json.dump(cube_data, f, indent=2)
    
    return {
        "success": True,
        "path": output_path,
        "frameCount": 81,
        "paletteSize": 256
    }


@server.tool("compare_cubes")
async def compare_cubes(cube1_path: str, cube2_path: str) -> dict:
    """Compare two quantized cube data files"""
    try:
        with open(cube1_path, 'r') as f:
            cube1 = json.load(f)
        with open(cube2_path, 'r') as f:
            cube2 = json.load(f)
        
        # Compare palettes
        palette1 = cube1.get("global_palette", [])
        palette2 = cube2.get("global_palette", [])
        
        palette_diff = 0
        for c1, c2 in zip(palette1, palette2):
            # Simple RGB distance
            diff = sum((a - b) ** 2 for a, b in zip(c1, c2)) ** 0.5
            palette_diff += diff
        
        if palette1:
            palette_diff /= len(palette1)
        
        # Compare frames
        frames1 = cube1.get("indexed_frames", [])
        frames2 = cube2.get("indexed_frames", [])
        
        frame_diffs = []
        for f1, f2 in zip(frames1, frames2):
            changed = sum(1 for p1, p2 in zip(f1, f2) if p1 != p2)
            frame_diffs.append(changed / len(f1) if f1 else 0)
        
        # Compare metrics
        meta1 = cube1.get("metadata", {})
        meta2 = cube2.get("metadata", {})
        
        return {
            "paletteDifference": palette_diff,
            "averageFrameDifference": sum(frame_diffs) / len(frame_diffs) if frame_diffs else 0,
            "maxFrameDifference": max(frame_diffs) if frame_diffs else 0,
            "deltaEDifference": abs(
                meta1.get("mean_delta_e", 0) - meta2.get("mean_delta_e", 0)
            ),
            "stabilityDifference": abs(
                cube1.get("temporal_metrics", {}).get("palette_stability", 0) -
                cube2.get("temporal_metrics", {}).get("palette_stability", 0)
            )
        }
    except Exception as e:
        return {
            "error": str(e)
        }


if __name__ == "__main__":
    asyncio.run(server.run())