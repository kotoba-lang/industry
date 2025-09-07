import asyncio
import glob
from pathlib import Path
from typing import List
from temporalio import workflow
from temporalio.common import RetryPolicy
from loguru import logger

from src.shared.models import VideoAnalysisRequest, VideoEmotionResult, AnalysisResult
from src.activities.humeai_analyzer import analyze_video_emotions
from src.activities.result_storage import save_emotion_analysis_result, save_batch_analysis_results, update_session_metadata


@workflow.defn
class VideoEmotionAnalysisWorkflow:
    """Temporal Workflow for analyzing emotions in a single video file using HumeAI"""

    @workflow.run
    async def run(self, session_id: str, video_filename: str) -> AnalysisResult:
        """Main workflow execution for single video file"""
        logger.info(f"Starting emotion analysis workflow for {video_filename} in session: {session_id}")

        try:
            # Step 1: Construct video path
            video_path = f"data/{session_id}/{video_filename}"

            # Check if video file exists
            if not Path(video_path).exists():
                logger.error(f"Video file not found: {video_path}")
                return AnalysisResult(
                    session_id=session_id,
                    results=[],
                    status="VIDEO_NOT_FOUND"
                )

            # Step 2: Create analysis request
            request = VideoAnalysisRequest(
                session_id=session_id,
                video_path=video_path,
                video_filename=video_filename
            )

            # Step 3: Analyze single video
            logger.info(f"Analyzing video: {video_filename}")
            analysis_result = await workflow.execute_activity(
                analyze_video_emotions,
                request,
                start_to_close_timeout="600s",  # 10 minutes timeout
                retry_policy=RetryPolicy(
                    maximum_attempts=3,
                    initial_interval="10s",
                    backoff_coefficient=2.0,
                    maximum_interval="60s"
                )
            )

            # Step 4: Save result
            logger.info(f"Saving analysis result for {video_filename}")
            saved_file = await workflow.execute_activity(
                save_emotion_analysis_result,
                analysis_result,
                start_to_close_timeout="30s",
                retry_policy=RetryPolicy(maximum_attempts=3)
            )

            # Step 5: Update session metadata
            await workflow.execute_activity(
                update_session_metadata,
                args=[session_id, [saved_file]],
                start_to_close_timeout="30s",
                retry_policy=RetryPolicy(maximum_attempts=3)
            )

            # Step 6: Return result
            logger.info(f"Successfully completed analysis for {video_filename}")
            return AnalysisResult(
                session_id=session_id,
                results=[analysis_result],
                status="COMPLETED",
                completed_at=workflow.now()
            )

        except Exception as e:
            logger.error(f"Workflow failed for {video_filename} in session {session_id}: {str(e)}")
            return AnalysisResult(
                session_id=session_id,
                results=[],
                status="FAILED"
            )


@workflow.defn
class BatchVideoAnalysisWorkflow:
    """Workflow to analyze multiple video files sequentially"""

    @workflow.run
    async def run(self, video_files: List[dict]) -> List[AnalysisResult]:
        """Analyze emotions for multiple video files sequentially"""
        logger.info(f"Starting batch analysis for {len(video_files)} video files")

        results = []

        # Process each video file sequentially
        for video_info in video_files:
            session_id = video_info["session_id"]
            video_filename = video_info["video_filename"]

            logger.info(f"Processing video: {video_filename} in session: {session_id}")

            try:
                # Execute child workflow for each video
                result = await workflow.execute_child_workflow(
                    VideoEmotionAnalysisWorkflow.run,
                    args=[session_id, video_filename],
                    id=f"emotion-analysis-{session_id}-{video_filename}",
                    task_queue="video-analysis-queue"
                )
                results.append(result)

                logger.info(f"Completed analysis for {video_filename}: {result.status}")

            except Exception as e:
                logger.error(f"Failed to analyze {video_filename} in session {session_id}: {str(e)}")
                results.append(AnalysisResult(
                    session_id=session_id,
                    results=[],
                    status="FAILED"
                ))

        logger.info(f"Batch analysis completed for {len(results)} video files")
        return results


# Activity to discover video files
async def discover_video_files(session_id: str) -> List[str]:
    """Discover all webm video files for a given session"""
    try:
        session_dir = Path(f"data/{session_id}")
        if not session_dir.exists():
            logger.warning(f"Session directory not found: {session_dir}")
            return []

        # Find all webm files in the session directory
        video_pattern = f"data/{session_id}/*.webm"
        video_files = glob.glob(video_pattern)

        logger.info(f"Found {len(video_files)} video files in session {session_id}")
        return sorted(video_files)  # Sort for consistent ordering

    except Exception as e:
        logger.error(f"Failed to discover video files for session {session_id}: {str(e)}")
        raise
