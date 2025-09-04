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

from client import start_batch_session_analysis, discover_available_sessions


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

    # Discover sessions
    print("🔍 Discovering available sessions...")
    session_ids = await discover_available_sessions()

    if not session_ids:
        print("❌ No sessions found in data directory")
        return

    print(f"📊 Found {len(session_ids)} sessions: {', '.join(session_ids[:5])}{'...' if len(session_ids) > 5 else ''}")

    # Confirm execution
    response = input(f"\n🚀 Start analysis for {len(session_ids)} sessions? (y/N): ")
    if response.lower() not in ['y', 'yes']:
        print("❌ Analysis cancelled")
        return

    # Start analysis
    print("\n⚡ Starting batch analysis...")
    try:
        results = await start_batch_session_analysis(session_ids)

        # Print results
        print("\n📈 Analysis Results:")
        print("-" * 30)
        successful = 0
        total_videos = 0

        for result in results:
            status_icon = "✅" if result.status == "COMPLETED" else "⚠️" if result.status == "PARTIALLY_COMPLETED" else "❌"
            print(f"{status_icon} {result.session_id}: {result.status} ({len(result.results)} videos)")
            if result.status in ["COMPLETED", "PARTIALLY_COMPLETED"]:
                successful += 1
            total_videos += len(result.results)

        print(f"\n🎉 Completed! {successful}/{len(session_ids)} sessions successful, {total_videos} videos analyzed")
        print("📁 Results saved in data/{session_id}/analysis_results/")

    except Exception as e:
        print(f"❌ Analysis failed: {str(e)}")
        print("Make sure Temporal server is running and worker is started")


if __name__ == "__main__":
    asyncio.run(main())
