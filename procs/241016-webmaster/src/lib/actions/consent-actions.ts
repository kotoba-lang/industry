"use server";

import { v4 as uuidv4 } from "uuid";
import { ConsentRecord } from "../db/schema/consent";
import { DemographicData } from "../db/schema/demographic";
import { createSupabaseServerClient } from "../supabase/server";

/*
 * 同意フォームからの人口統計データと同意情報を保存
 */
export async function saveConsentAndDemographicData(
  demographicInfo: DemographicData,
  consentInfo: ConsentRecord,
  contextInfo?: {
    ipAddress?: string;
    userAgent?: string;
    studyId?: string;
  },
): Promise<{
  success: boolean;
  userId: string;
  consent: ConsentRecord | null;
  demographic?: DemographicData | null;
}> {
  console.log("saveConsentAndDemographicData");
  console.log(demographicInfo);
  console.log(consentInfo);
  console.log(contextInfo);
  try {
    const supabase = await createSupabaseServerClient();

    // ユーザーIDを生成（セッションIDや認証済みユーザーIDがあればそれを使用）
    const userId = uuidv4();

    // 同意記録を保存
    const consentData = {
      user_id: userId,
      consent_given: consentInfo.consentGiven,
      consent_version: consentInfo.consentVersion || "1.0",
      consent_text: consentInfo.consentText,
      ip_address: contextInfo?.ipAddress,
      user_agent: contextInfo?.userAgent,
      study_id: contextInfo?.studyId,
    };

    const { data: consentResult, error: consentError } = await supabase
      .from("consent_records")
      .insert(consentData)
      .select()
      .single();

    if (consentError) {
      console.error("Error saving consent data:", consentError);
      throw consentError;
    }

    // 同意が得られた場合のみ人口統計データを保存
    if (consentInfo.consentGiven) {
      const demographicRecord = {
        user_id: userId,
        age_group: demographicInfo.ageGroup || "prefer-not-to-say",
        gender: demographicInfo.gender || "prefer-not-to-say",
        ethnicity: demographicInfo.ethnicity || "prefer-not-to-say",
        income: demographicInfo.income || "prefer-not-to-say",
        ip_address: contextInfo?.ipAddress,
        user_agent: contextInfo?.userAgent,
        study_id: contextInfo?.studyId,
        consent_version: consentInfo.consentVersion || "1.0",
      };

      const { data: demographicResult, error: demographicError } =
        await supabase
          .from("demographic_data")
          .insert(demographicRecord)
          .select()
          .single();

      if (demographicError) {
        console.error("Error saving demographic data:", demographicError);
        throw demographicError;
      }

      return {
        success: true,
        userId,
        consent: consentResult,
        demographic: demographicResult,
      };
    }

    return {
      success: consentInfo.consentGiven,
      userId,
      consent: consentResult,
    };
  } catch (error) {
    console.error("Error saving consent and demographic data:", error);
    return {
      success: false,
      userId: "",
      consent: null,
      demographic: null,
    };
  }
}
