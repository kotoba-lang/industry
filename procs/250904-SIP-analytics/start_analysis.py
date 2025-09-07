#!/usr/bin/env python3
"""
Quick start script for video emotion analysis workflow
"""

import asyncio
import os
import sys
from pathlib import Path

# Add src to path
sys.path.insert(0, str(Path(__file__).parent / "src"))

from client import start_batch_file_analysis, discover_all_video_files


async def main():
    """Quick analysis of all available sessions"""
    print("🎬 Video Emotion Analysis Workflow")
    print("=" * 50)

    # Check if config exists
    config_path = Path("config.yaml")
    if not config_path.exists():
        print("❌ config.yaml not found!")
        print("Please copy config.example.yaml to config.yaml and configure your HumeAI API key")
        return

    # Check if HumeAI API key is configured
    if "your_hume_api_key_here" in config_path.read_text():
        print("❌ HumeAI API key not configured!")
        print("Please set your API key in config.yaml")
        return

    # Discover video files
    print("🔍 Discovering available video files...")
    video_files = await discover_all_video_files()

    if not video_files:
        print("❌ No video files found in data directory")
        return

    print(f"📊 Found {len(video_files)} video files")

    # Group by session for display
    session_groups = {}
    for vf in video_files:
        session_id = vf["session_id"]
        if session_id not in session_groups:
            session_groups[session_id] = []
        session_groups[session_id].append(vf["video_filename"])

    print("📂 Sessions and files:")
    for session_id, files in session_groups.items():
        print(f"  {session_id}: {len(files)} files")

    # Confirm execution
    response = input(f"\n🚀 Start analysis for {len(video_files)} video files? (y/N): ")
    if response.lower() not in ['y', 'yes']:
        print("❌ Analysis cancelled")
        return

    # Start analysis
    print("\n⚡ Starting batch analysis...")
    try:
        results = await start_batch_file_analysis(video_files)

        # Print results
        print("\n📈 Analysis Results:")
        print("-" * 50)
        successful = 0
        failed = 0

        for result in results:
            if result.status == "COMPLETED":
                status_icon = "✅"
                successful += 1
            else:
                status_icon = "❌"
                failed += 1

            video_filename = result.results[0].video_filename if result.results else "unknown"
            print(f"{status_icon} {result.session_id}/{video_filename}: {result.status}")

        print(f"\n🎉 Completed! {successful}/{len(video_files)} files successful, {failed} failed")
        print("📁 Results saved in data/{session_id}/analysis_results/")

    except Exception as e:
        print(f"❌ Analysis failed: {str(e)}")
        print("Make sure Temporal server is running and worker is started")


if __name__ == "__main__":
    asyncio.run(main())
