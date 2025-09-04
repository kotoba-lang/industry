import asyncio
import os
from temporalio.client import Client
from temporalio.worker import Worker
from temporalio.runtime import Runtime
from loguru import logger

from src.workflows.video_emotion_analysis import VideoEmotionAnalysisWorkflow, BatchVideoAnalysisWorkflow
from src.activities.humeai_analyzer import analyze_video_emotions
from src.activities.result_storage import save_emotion_analysis_result, save_batch_analysis_results, update_session_metadata


async def run_worker():
    """Run the Temporal worker"""
    try:
        # Configure logging
        logger.add("logs/worker.log", rotation="10 MB", level="INFO")
        logger.info("Starting Temporal worker...")

        # Create Temporal client
        client = await Client.connect(
            os.getenv("TEMPORAL_ADDRESS", "localhost:7233"),
            namespace=os.getenv("TEMPORAL_NAMESPACE", "default")
        )

        # Create worker
        worker = Worker(
            client,
            task_queue="video-analysis-queue",
            workflows=[
                VideoEmotionAnalysisWorkflow,
                BatchVideoAnalysisWorkflow
            ],
            activities=[
                analyze_video_emotions,
                save_emotion_analysis_result,
                save_batch_analysis_results,
                update_session_metadata
            ]
        )

        logger.info("Worker started successfully. Listening for tasks...")
        await worker.run()

    except KeyboardInterrupt:
        logger.info("Worker shutdown requested")
    except Exception as e:
        logger.error(f"Worker failed: {str(e)}")
        raise


if __name__ == "__main__":
    # Ensure logs directory exists
    os.makedirs("logs", exist_ok=True)

    # Configure runtime for better performance
    runtime = Runtime(
        telemetry=Runtime.TelemetryConfig(
            metrics=Runtime.MetricsConfig(
                prometheus_bind_address="0.0.0.0:9090"
            )
        )
    )

    # Run the worker
    asyncio.run(run_worker())
