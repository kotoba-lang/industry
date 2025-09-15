import { NextRequest, NextResponse } from "next/server";

// Mock data for demonstration - in production, this would come from a database
const mockParticipants = [
  {
    id: "550e8400-e29b-41d4-a716-446655440000",
    age: 25,
    gender: "female",
    handedness: "right",
    createdAt: new Date("2024-09-15T10:00:00Z"),
    sessionCount: 2,
    lastActivity: new Date("2024-09-15T14:30:00Z"),
    status: "completed"
  },
  {
    id: "550e8400-e29b-41d4-a716-446655440001",
    age: 32,
    gender: "male",
    handedness: "left",
    createdAt: new Date("2024-09-14T09:15:00Z"),
    sessionCount: 1,
    lastActivity: new Date("2024-09-14T11:45:00Z"),
    status: "in_progress"
  },
  {
    id: "550e8400-e29b-41d4-a716-446655440002",
    age: 28,
    gender: "female",
    handedness: "right",
    createdAt: new Date("2024-09-13T16:20:00Z"),
    sessionCount: 2,
    lastActivity: new Date("2024-09-13T17:10:00Z"),
    status: "completed"
  }
];

const mockSessionData = [
  {
    participantId: "550e8400-e29b-41d4-a716-446655440000",
    sessionId: "session-1",
    sessionType: "session-1",
    startTime: "2024-09-15T10:30:00Z",
    endTime: "2024-09-15T11:15:00Z",
    wordResponses: [
      {
        stimulusWord: { word: "愛", key: "ai" },
        responseWord: "優しさ",
        reactionTimeMs: 1250,
        isDelayed: false
      },
      {
        stimulusWord: { word: "力", key: "chikara" },
        responseWord: "強さ",
        reactionTimeMs: 980,
        isDelayed: false
      },
      {
        stimulusWord: { word: "光", key: "hikari" },
        responseWord: "明るさ",
        reactionTimeMs: 1450,
        isDelayed: true
      }
    ],
    averageReactionTime: 1226.67,
    emotionData: [
      { emotion: "joy", confidence: 0.85, timestamp: "2024-09-15T10:35:00Z" },
      { emotion: "surprise", confidence: 0.72, timestamp: "2024-09-15T10:45:00Z" },
      { emotion: "calm", confidence: 0.90, timestamp: "2024-09-15T11:00:00Z" }
    ]
  },
  {
    participantId: "550e8400-e29b-41d4-a716-446655440000",
    sessionId: "session-2",
    sessionType: "session-2",
    startTime: "2024-09-15T14:00:00Z",
    endTime: "2024-09-15T14:45:00Z",
    wordResponses: [
      {
        stimulusWord: { word: "風", key: "kaze" },
        responseWord: "自由",
        reactionTimeMs: 1100,
        isDelayed: false
      },
      {
        stimulusWord: { word: "海", key: "umi" },
        responseWord: "広大",
        reactionTimeMs: 1350,
        isDelayed: false
      }
    ],
    averageReactionTime: 1225,
    emotionData: [
      { emotion: "wonder", confidence: 0.88, timestamp: "2024-09-15T14:10:00Z" },
      { emotion: "peace", confidence: 0.76, timestamp: "2024-09-15T14:30:00Z" }
    ]
  }
];

export async function GET(request: NextRequest) {
  const { searchParams } = new URL(request.url);
  const type = searchParams.get('type');
  const participantId = searchParams.get('participantId');

  try {
    switch (type) {
      case 'participants':
        return NextResponse.json({
          success: true,
          data: mockParticipants,
          total: mockParticipants.length
        });

      case 'participant':
        if (!participantId) {
          return NextResponse.json({
            error: "Participant ID is required"
          }, { status: 400 });
        }

        const participant = mockParticipants.find(p => p.id === participantId);
        if (!participant) {
          return NextResponse.json({
            error: "Participant not found"
          }, { status: 404 });
        }

        return NextResponse.json({
          success: true,
          data: participant
        });

      case 'sessions':
        const sessions = participantId
          ? mockSessionData.filter(s => s.participantId === participantId)
          : mockSessionData;

        return NextResponse.json({
          success: true,
          data: sessions,
          total: sessions.length
        });

      case 'analytics':
        // Aggregate analytics data
        const totalParticipants = mockParticipants.length;
        const completedSessions = mockParticipants.filter(p => p.status === 'completed').length;
        const averageSessionDuration = 2700; // 45 minutes in seconds
        const averageReactionTime = mockSessionData.reduce((acc, session) => {
          return acc + session.averageReactionTime;
        }, 0) / mockSessionData.length;

        const emotionDistribution = mockSessionData
          .flatMap(session => session.emotionData)
          .reduce((acc, emotion) => {
            acc[emotion.emotion] = (acc[emotion.emotion] || 0) + 1;
            return acc;
          }, {} as Record<string, number>);

        return NextResponse.json({
          success: true,
          data: {
            totalParticipants,
            completedSessions,
            completionRate: (completedSessions / totalParticipants) * 100,
            averageSessionDuration,
            averageReactionTime,
            emotionDistribution,
            totalSessions: mockSessionData.length
          }
        });

      case 'reaction-times':
        const reactionTimeData = mockSessionData.flatMap(session =>
          session.wordResponses.map(response => ({
            participantId: session.participantId,
            sessionType: session.sessionType,
            stimulusWord: response.stimulusWord.word,
            responseWord: response.responseWord,
            reactionTimeMs: response.reactionTimeMs,
            isDelayed: response.isDelayed,
            timestamp: session.startTime
          }))
        );

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
