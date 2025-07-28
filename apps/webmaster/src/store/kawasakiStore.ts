import { create } from "zustand";
import { persist } from "zustand/middleware";
import { TestResults } from "@/components/jung-voice-assessment/types";

interface VoiceAssessmentData {
    userId: string;
    assessmentId: string;
    results: TestResults;
    timestamp: string;
}

interface KawasakiState {
    voiceAssessments: VoiceAssessmentData[];
    updateVoiceAssessment: (data: VoiceAssessmentData) => void;
    // 他の Kawasaki Model 関連の状態と関数
}

export const useKawasakiStore = create<KawasakiState>()(
    persist(
        (set) => ({
            voiceAssessments: [],

            updateVoiceAssessment: (data) =>
                set((state) => {
                    // 既存の評価があれば更新、なければ追加
                    const existingIndex = state.voiceAssessments.findIndex(
                        (assessment) =>
                            assessment.assessmentId === data.assessmentId,
                    );

                    if (existingIndex >= 0) {
                        const updatedAssessments = [...state.voiceAssessments];
                        updatedAssessments[existingIndex] = data;
                        return { voiceAssessments: updatedAssessments };
                    } else {
                        return {
                            voiceAssessments: [...state.voiceAssessments, data],
                        };
                    }
                }),
            // 他の Kawasaki Model 関連の関数
        }),
        {
            name: "kawasaki-model-storage",
            // セキュリティのため、必要に応じて暗号化を検討
        },
    ),
);
