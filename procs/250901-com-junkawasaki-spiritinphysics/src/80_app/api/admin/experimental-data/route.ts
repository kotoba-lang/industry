import { NextRequest, NextResponse } from "next/server";
import { storageAdapter } from "@/50_adapters";
import { emotionAnalysisAdapter } from "@/50_adapters";
import { parseWordResponsesFromEvents, getParticipantStatistics } from "@/lib/data-loader";

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const type = searchParams.get('type');
  const participantId = searchParams.get('participantId');

  try {
    switch (type) {
      case 'participants':
        // Kuzuデータベースの初期化
        await initializeKuzuDatabase();
        const participants = await storageAdapter.loadAllParticipants();
        const participantStats = getParticipantStatistics(participants);

        // Transform to match expected format
        const formattedParticipants = participants.map(p => ({
          id: p.id,
          age: null, // Age not available in current data
          gender: null, // Gender not available in current data
          handedness: null, // Handedness not available in current data
          createdAt: p.agreedAt,
          sessionCount: p.hasSessionData ? 1 : 0, // Simplified
          lastActivity: p.agreedAt,
          status: p.hasSessionData ? 'completed' : 'in_progress',
          hasVideoFiles: p.hasVideoFiles,
          videoFiles: p.videoFiles
        }));

        return NextResponse.json({
          success: true,
          data: formattedParticipants,
          total: formattedParticipants.length,
          stats: participantStats
        });

      case 'participant':
        if (!participantId) {
          return NextResponse.json({
            error: "Participant ID is required"
          }, { status: 400 });
        }

        const participants_list = await storageAdapter.loadAllParticipants();
        const participant = participants_list.find(p => p.id === participantId);
        if (!participant) {
          return NextResponse.json({
            error: "Participant not found"
          }, { status: 404 });
        }

        return NextResponse.json({
          success: true,
          data: {
            id: participant.id,
            signature: participant.signature,
            agreedAt: participant.agreedAt,
            hasSessionData: participant.hasSessionData,
            hasVideoFiles: participant.hasVideoFiles,
            videoFiles: participant.videoFiles
          }
        });

      case 'sessions':
        const allSessionData = loadAllSessionData();
        const sessions = participantId
          ? allSessionData.filter(s => s.participantId === participantId)
          : allSessionData;

        // Transform session data to match expected format
        const formattedSessions = sessions.map(({ participantId, sessionData }) => {
          const wordResponses = parseWordResponsesFromEvents(sessionData.events);

          // Extract session start/end times from events
          const sessionStartedEvent = sessionData.events.find(e => e.type === 'session_started');
          const sessionStartTime = sessionStartedEvent
            ? new Date(sessionStartedEvent.timestamp).toISOString()
            : new Date().toISOString();

          const sessionEndedEvent = sessionData.events
            .filter(e => e.type === 'response_window_closed')
            .pop();
          const sessionEndTime = sessionEndedEvent
            ? new Date(sessionEndedEvent.timestamp).toISOString()
            : sessionStartTime;

          return {
            participantId,
            sessionId: `session-${sessionStartedEvent?.payload?.session || 1}`,
            sessionType: `session-${sessionStartedEvent?.payload?.session || 1}`,
            startTime: sessionStartTime,
            endTime: sessionEndTime,
            wordResponses,
            averageReactionTime: wordResponses.length > 0
              ? wordResponses.reduce((acc, r) => acc + r.reactionTimeMs, 0) / wordResponses.length
              : 0,
            emotionData: [] // Emotion data not available in current structure
          };
        });

        return NextResponse.json({
          success: true,
          data: formattedSessions,
          total: formattedSessions.length
        });

      case 'analytics':
        // Kuzuデータベースの初期化
        await initializeKuzuDatabase();
        const participants_for_analytics = await storageAdapter.loadAllParticipants();
        const stats = getParticipantStatistics(participants_for_analytics);
        const allSessions = await loadAllSessionData();

        // Calculate reaction time statistics
        let totalReactionTime = 0;
        let totalResponses = 0;

        allSessions.forEach(({ sessionData }) => {
          const wordResponses = parseWordResponsesFromEvents(sessionData.events);
          wordResponses.forEach(response => {
            totalReactionTime += response.reactionTimeMs;
            totalResponses += 1;
          });
        });

        const averageReactionTime = totalResponses > 0 ? totalReactionTime / totalResponses : 0;

        // Kuzuから感情統計を取得
        const emotionStats = await emotionAnalysisAdapter.getEmotionStatistics();
        const emotionDistribution: Record<string, number> = {};
        emotionStats.dominantEmotions.forEach(item => {
          emotionDistribution[item.emotion] = item.count;
        });

        return NextResponse.json({
          success: true,
          data: {
            totalParticipants: stats.totalParticipants,
            completedSessions: stats.participantsWithSessionData,
            completionRate: stats.completionRate,
            averageSessionDuration: 2700, // Estimated 45 minutes in seconds
            averageReactionTime,
            emotionDistribution,
            totalSessions: allSessions.length,
            participantsWithVideo: stats.participantsWithVideo
          }
        });

      case 'reaction-times':
        const allSessionData_rt = await loadAllSessionData();
        const reactionTimeData = allSessionData_rt.flatMap(({ participantId, sessionData }) => {
          const wordResponses = parseWordResponsesFromEvents(sessionData.events);

          return wordResponses.map(response => ({
            participantId,
            sessionType: 'session-1', // Simplified
            stimulusWord: response.stimulusWord,
            responseWord: response.responseWord,
            reactionTimeMs: response.reactionTimeMs,
            isDelayed: response.isDelayed,
            timestamp: new Date(response.timestamp).toISOString()
          }));
        });

        return NextResponse.json({
          success: true,
          data: reactionTimeData
        });

      default:
        return NextResponse.json({
          error: "Invalid type parameter. Use: participants, participant, sessions, analytics, reaction-times"
        }, { status: 400 });
    }
  } catch (error) {
    console.error("Error fetching experimental data:", error);
    return NextResponse.json({
      error: "Internal server error"
    }, { status: 500 });
  }
}
