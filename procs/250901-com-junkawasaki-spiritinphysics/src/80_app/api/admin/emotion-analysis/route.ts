// LLM-BOUNDARY: 80_app - app/(segments)/...（RSC & Client）

import { NextRequest, NextResponse } from "next/server";
import { emotionAnalysisAdapter } from "@/50_adapters";
import { WorkflowSupervisor } from "@/70_supervisors";
import { foldEmotionStatistics } from "@/30_fold";

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const action = searchParams.get('action');
  const participantId = searchParams.get('participantId');
  const videoFile = searchParams.get('videoFile');
  const sessionType = searchParams.get('sessionType');

  try {
    switch (action) {
      case 'analyze-single':
        if (!participantId || !videoFile || !sessionType) {
          return NextResponse.json({
            error: "participantId, videoFile, and sessionType are required"
          }, { status: 400 });
        }

        const result = await emotionAnalysisAdapter.analyzeVideoEmotions(participantId, videoFile, sessionType);
        if (!result) {
          return NextResponse.json({
            error: "Failed to analyze video emotions"
          }, { status: 500 });
        }

        return NextResponse.json({
          success: true,
          data: result
        });

      case 'analyze-all':
        if (!participantId) {
          return NextResponse.json({
            error: "participantId is required"
          }, { status: 400 });
        }

        const allResults = await emotionAnalysisAdapter.analyzeAllParticipantVideos(participantId);
        return NextResponse.json({
          success: true,
          data: allResults,
          count: allResults.length
        });

      case 'get-results':
        if (!participantId) {
          return NextResponse.json({
            error: "participantId is required"
          }, { status: 400 });
        }

        const savedResults = await emotionAnalysisAdapter.loadEmotionAnalysis(participantId);
        const stats = foldEmotionStatistics(savedResults);

        return NextResponse.json({
          success: true,
          data: savedResults,
          statistics: stats
        });

      case 'get-statistics':
        // 全参加者の統計情報をまとめて取得
        const participantIds = [
          "144b325f-5966-4d59-a629-f2ca421388cc",
          "15592cdb-86cf-4baf-86f5-66184169ee39",
          "25111604-c7db-4bfd-8662-e55060e332d6",
          "2a0d7a69-f953-4c29-87a5-8a8e4e8bd413",
          "4512513e-9132-4556-9858-bac08f28037f",
          "5346d514-e501-457a-aff1-55c92074a6f2",
          "5ac869a3-b8db-49c3-9362-3e149a5415e9",
          "7dda0261-a6f4-4208-bd61-4244380d277f",
          "a4e1b8f4-e267-41a7-acfc-07fe1b7c06fb",
          "a5d58eb8-a8c0-4b19-b4e3-67c391b530db",
          "ad96101f-a7a8-4d71-8d82-c0478975c40b",
          "e41a9cd2-d803-49a8-9020-0260e55cd03e"
        ];

        // 全参加者の結果を集計
        const globalResults: any[] = [];
        const participantPromises = participantIds.map(async id => {
          const results = await emotionAnalysisAdapter.loadEmotionAnalysis(id);
          return results;
        });
        const allParticipantResults = await Promise.all(participantPromises);
        allParticipantResults.forEach(results => {
          globalResults.push(...results);
        });

        const globalStats = foldEmotionStatistics(globalResults);

        return NextResponse.json({
          success: true,
          data: {
            totalParticipants: participantIds.length,
            totalAnalyses: globalResults.length,
            statistics: globalStats,
            dominantEmotions: globalStats?.dominantEmotions || []
          }
        });

      default:
        return NextResponse.json({
          error: "Invalid action parameter. Use: analyze-single, analyze-all, get-results, get-statistics"
        }, { status: 400 });
    }
  } catch (error) {
    console.error("Error in emotion analysis:", error);
    return NextResponse.json({
      error: "Internal server error"
    }, { status: 500 });
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { action, participantId, videoFile, sessionType, priority = 'normal' } = body;

    switch (action) {
      case 'analyze-single-workflow':
        if (!participantId || !videoFile || !sessionType) {
          return NextResponse.json({
            error: "participantId, videoFile, and sessionType are required"
          }, { status: 400 });
        }

        console.log(`Starting direct video analysis for ${participantId}/${videoFile}`);

        // ワークフロー監視を使用して分析を実行
        const result = await emotionAnalysisAdapter.analyzeVideoEmotions(participantId, videoFile, sessionType);

        return NextResponse.json({
          success: true,
          message: 'Video analysis completed',
          data: {
            participantId,
            videoFile,
            sessionType,
            priority,
            result,
          }
        });

      case 'analyze-batch-workflow':
        if (!participantId) {
          return NextResponse.json({
            error: "participantId is required for batch analysis"
          }, { status: 400 });
        }

        console.log(`Starting batch analysis workflow for participant: ${participantId}`);

        try {
          // ワークフロー監視を使用してバッチ分析を開始
          await WorkflowSupervisor.startEmotionAnalysisWorkflow(participantId);

          return NextResponse.json({
            success: true,
            message: `Batch analysis workflow started for ${participantId}`,
            data: {
              participantId,
              priority,
            }
          });
        } catch (error) {
          console.error('Batch analysis workflow error:', error);
          return NextResponse.json({
            error: error instanceof Error ? error.message : 'Batch analysis workflow failed'
          }, { status: 500 });
        }

      case 'analyze-all-workflow':
        console.log('Starting batch analysis workflow for all participants');

        try {
          // 全参加者のバッチ分析ワークフローを開始
          await WorkflowSupervisor.startBatchAnalysisWorkflow();

          return NextResponse.json({
            success: true,
            message: 'Batch analysis workflow started for all participants',
            data: {
              priority,
            }
          });
        } catch (error) {
          console.error('All participants analysis workflow error:', error);
          return NextResponse.json({
            error: error instanceof Error ? error.message : 'All participants analysis workflow failed'
          }, { status: 500 });
        }

      case 'analyze-batch':
        // 既存の同期バッチ分析（後方互換性のため維持）
        if (!participantId) {
          return NextResponse.json({
            error: "participantId is required for batch analysis"
          }, { status: 400 });
        }

        console.log(`Starting synchronous batch emotion analysis for participant: ${participantId}`);
        const batchResults = await emotionAnalysisAdapter.analyzeAllParticipantVideos(participantId);

        return NextResponse.json({
          success: true,
          message: `Synchronous batch analysis completed for ${participantId}`,
          data: batchResults,
          count: batchResults.length
        });

      default:
        return NextResponse.json({
          error: "Invalid action. Use: analyze-single-workflow, analyze-batch-workflow, analyze-all-workflow, or analyze-batch"
        }, { status: 400 });
    }

  } catch (error) {
    console.error("Error in emotion analysis POST:", error);
    return NextResponse.json({
      error: "Internal server error"
    }, { status: 500 });
  }
}
