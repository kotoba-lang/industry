import asyncio
import os
import sys
from pathlib import Path
from temporalio.client import Client
from loguru import logger

from src.workflows.video_emotion_analysis import VideoEmotionAnalysisWorkflow, BatchVideoAnalysisWorkflow
from src.shared.models import AnalysisResult


async def start_single_file_analysis(session_id: str, video_filename: str) -> AnalysisResult:
    """Start emotion analysis for a single video file"""
    try:
        logger.info(f"Starting emotion analysis workflow for {video_filename} in session: {session_id}")

        # Create Temporal client
        client = await Client.connect(
            os.getenv("TEMPORAL_ADDRESS", "localhost:7233"),
            namespace=os.getenv("TEMPORAL_NAMESPACE", "default")
        )

        # Start the workflow
        workflow_id = f"emotion-analysis-{session_id}-{video_filename}"
        handle = await client.start_workflow(
            VideoEmotionAnalysisWorkflow.run,
            args=[session_id, video_filename],
            id=workflow_id,
            task_queue="video-analysis-queue"
        )

        logger.info(f"Workflow started with ID: {workflow_id}")

        # Wait for completion
        result = await handle.result()
        logger.info(f"Workflow completed for {video_filename} in session {session_id}: {result.status}")

        return result

    except Exception as e:
        logger.error(f"Failed to start workflow for {video_filename} in session {session_id}: {str(e)}")
        raise


async def start_single_session_analysis(session_id: str) -> AnalysisResult:
    """Start emotion analysis for all videos in a single session"""
    try:
        logger.info(f"Starting emotion analysis workflow for session: {session_id}")

        # Discover all video files in the session
        video_files = await discover_video_files_in_session(session_id)
        if not video_files:
            logger.warning(f"No video files found for session: {session_id}")
            return AnalysisResult(
                session_id=session_id,
                results=[],
                status="NO_VIDEOS_FOUND"
            )

        # Create video file info list
        video_file_infos = [
            {"session_id": session_id, "video_filename": video_filename}
            for video_filename in video_files
        ]

        # Start batch analysis
        results = await start_batch_file_analysis(video_file_infos)

        # Return aggregated result
        successful_results = [r for r in results if r.status == "COMPLETED"]
        status = "COMPLETED" if len(successful_results) == len(results) else "PARTIALLY_COMPLETED"

        return AnalysisResult(
            session_id=session_id,
            results=[r.results[0] for r in successful_results if r.results],
            status=status
        )

    except Exception as e:
        logger.error(f"Failed to start workflow for session {session_id}: {str(e)}")
        raise


async def start_batch_file_analysis(video_files: list) -> list:
    """Start emotion analysis for multiple video files"""
    try:
        logger.info(f"Starting batch analysis workflow for {len(video_files)} video files")

        # Create Temporal client
        client = await Client.connect(
            os.getenv("TEMPORAL_ADDRESS", "localhost:7233"),
            namespace=os.getenv("TEMPORAL_NAMESPACE", "default")
        )

        # Start the batch workflow
        workflow_id = f"batch-emotion-analysis-{len(video_files)}-files"
        handle = await client.start_workflow(
            BatchVideoAnalysisWorkflow.run,
            video_files,
            id=workflow_id,
            task_queue="video-analysis-queue"
        )

        logger.info(f"Batch workflow started with ID: {workflow_id}")

        # Wait for completion
        results = await handle.result()
        logger.info(f"Batch workflow completed: {len(results)} results")

        return results

    except Exception as e:
        logger.error(f"Failed to start batch workflow: {str(e)}")
        raise


async def start_batch_session_analysis(session_ids: list) -> list:
    """Start emotion analysis for multiple sessions"""
    try:
        logger.info(f"Starting batch analysis workflow for {len(session_ids)} sessions")

        # Collect all video files from all sessions
        all_video_files = []
        for session_id in session_ids:
            video_files = await discover_video_files_in_session(session_id)
            for video_filename in video_files:
                all_video_files.append({
                    "session_id": session_id,
                    "video_filename": video_filename
                })

        if not all_video_files:
            logger.warning("No video files found in any session")
            return []

        logger.info(f"Found {len(all_video_files)} total video files across {len(session_ids)} sessions")

        # Start batch analysis for all files
        results = await start_batch_file_analysis(all_video_files)

        return results

    except Exception as e:
        logger.error(f"Failed to start batch workflow: {str(e)}")
        raise


async def discover_available_sessions() -> list:
    """Discover all available session IDs from data directory"""
    try:
        data_dir = Path("data")
        if not data_dir.exists():
            logger.error("Data directory not found")
            return []

        session_dirs = [d.name for d in data_dir.iterdir() if d.is_dir()]
        logger.info(f"Found {len(session_dirs)} session directories")
        return sorted(session_dirs)

    except Exception as e:
        logger.error(f"Failed to discover sessions: {str(e)}")
        return []


async def discover_video_files_in_session(session_id: str) -> list:
    """Discover all video files in a specific session"""
    try:
        session_dir = Path(f"data/{session_id}")
        if not session_dir.exists():
            logger.error(f"Session directory not found: {session_dir}")
            return []

        video_files = [f.name for f in session_dir.glob("*.webm")]
        logger.info(f"Found {len(video_files)} video files in session {session_id}")
        return sorted(video_files)

    except Exception as e:
        logger.error(f"Failed to discover video files in session {session_id}: {str(e)}")
        return []


async def discover_all_video_files() -> list:
    """Discover all video files across all sessions"""
    try:
        sessions = await discover_available_sessions()
        all_files = []

        for session_id in sessions:
            video_files = await discover_video_files_in_session(session_id)
            for video_filename in video_files:
                all_files.append({
                    "session_id": session_id,
                    "video_filename": video_filename
                })

        logger.info(f"Found {len(all_files)} total video files across {len(sessions)} sessions")
        return all_files

    except Exception as e:
        logger.error(f"Failed to discover all video files: {str(e)}")
        return []


async def main():
    """Main client function"""
    # Configure logging
    logger.add("logs/client.log", rotation="10 MB", level="INFO")

    if len(sys.argv) < 2:
        print("Usage:")
        print("  python src/client.py file <session_id> <video_filename>")
        print("  python src/client.py session <session_id>")
        print("  python src/client.py batch-files")
        print("  python src/client.py batch-sessions <session_id1> <session_id2> ...")
        print("  python src/client.py batch-all")
        return

    command = sys.argv[1]

    try:
        if command == "file":
            if len(sys.argv) < 4:
                print("Please provide session ID and video filename")
                return

            session_id = sys.argv[2]
            video_filename = sys.argv[3]
            result = await start_single_file_analysis(session_id, video_filename)
            print(f"Analysis completed for {video_filename} in session {session_id}")
            print(f"Status: {result.status}")
            print(f"Results saved: {len(result.results)} emotion analysis")

        elif command == "session":
            if len(sys.argv) < 3:
                print("Please provide a session ID")
                return

            session_id = sys.argv[2]
            result = await start_single_session_analysis(session_id)
            print(f"Analysis completed for session {session_id}")
            print(f"Status: {result.status}")
            print(f"Videos analyzed: {len(result.results)}")

        elif command == "batch-files":
            video_files = await discover_all_video_files()
            if not video_files:
                print("No video files found in data directory")
                return

            print(f"Starting analysis for all {len(video_files)} video files")
            results = await start_batch_file_analysis(video_files)

            successful = sum(1 for r in results if r.status == "COMPLETED")
            print(f"Batch analysis completed: {successful}/{len(results)} files successful")

            for result in results:
                status_icon = "✅" if result.status == "COMPLETED" else "❌"
                print(f"  {status_icon} {result.session_id}/{result.results[0].video_filename if result.results else 'unknown'}: {result.status}")

        elif command == "batch-sessions":
            if len(sys.argv) < 3:
                print("Please provide session IDs")
                return

            session_ids = sys.argv[2:]
            results = await start_batch_session_analysis(session_ids)

            successful = sum(1 for r in results if r.status == "COMPLETED")
            print(f"Batch analysis completed for {len(results)} video files across {len(session_ids)} sessions")
            print(f"Success rate: {successful}/{len(results)}")

            for result in results:
                status_icon = "✅" if result.status == "COMPLETED" else "❌"
                print(f"  {status_icon} {result.session_id}: {result.status}")

        elif command == "batch-all":
            session_ids = await discover_available_sessions()
            if not session_ids:
                print("No sessions found in data directory")
                return

            print(f"Starting analysis for all {len(session_ids)} sessions: {', '.join(session_ids)}")
            results = await start_batch_session_analysis(session_ids)

            successful = sum(1 for r in results if r.status == "COMPLETED")
            total_files = sum(len(r.results) for r in results if r.results)
            print(f"Batch analysis completed: {successful}/{len(results)} files successful")
            print(f"Total videos analyzed: {total_files}")

            for result in results:
                status_icon = "✅" if result.status == "COMPLETED" else "❌"
                print(f"  {status_icon} {result.session_id}: {result.status} ({len(result.results)} videos)")

        else:
            print(f"Unknown command: {command}")
            print("Available commands:")
            print("  file <session_id> <video_filename>    - Analyze single video file")
            print("  session <session_id>                  - Analyze all videos in session")
            print("  batch-files                          - Analyze all video files sequentially")
            print("  batch-sessions <session_id1> ...      - Analyze videos from specific sessions")
            print("  batch-all                            - Analyze all videos from all sessions")

    except Exception as e:
        logger.error(f"Client execution failed: {str(e)}")
        print(f"Error: {str(e)}")
        sys.exit(1)


if __name__ == "__main__":
    # Ensure logs directory exists
    os.makedirs("logs", exist_ok=True)

    # Run the client
    asyncio.run(main())
