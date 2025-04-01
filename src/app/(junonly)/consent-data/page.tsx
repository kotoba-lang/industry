import { createSupabaseServerClient } from '@/lib/supabase/server'

// Define interfaces for the data (should match the ones in queries.ts)
interface ConsentRecord {
  id: string
  user_id: string
  created_at: string
  consent_given: boolean
  consent_version: string
  consent_text?: string
  ip_address?: string
  user_agent?: string
  study_id?: string
  researcher_note?: string
}

interface DemographicData {
  id: string
  user_id: string
  created_at: string
  age_group: string
  gender: string
  ethnicity: string
  income: string
  ip_address?: string
  user_agent?: string
  study_id?: string
  consent_version?: string
}

export const dynamic = 'force-dynamic'
export const revalidate = 0

export default async function ConsentDataPage() {
  let consentData: ConsentRecord[] = []
  let demographicData: DemographicData[] = []
  let error = null

  try {
    const supabase = await createSupabaseServerClient();
    const { data: consentData, error: consentError } = await supabase
      .from('consent_records')
      .select('*')
      .order('created_at', { ascending: false });

    if (consentError) {
      throw consentError;
    }

    const { data: demographicData, error: demographicError } = await supabase
      .from('demographic_data')
      .select('*')
      .order('created_at', { ascending: false });

    if (demographicError) {
      throw demographicError;
    }
  } catch (err) {
    console.error('Error fetching data:', err)
    error = err instanceof Error ? err.message : 'Unknown error occurred'
  }

  return (
    <div className="container mx-auto py-8">
      <h1 className="text-3xl font-bold mb-6">研究参加者データ</h1>
      
      {error && (
        <div className="bg-red-100 border border-red-400 text-red-700 px-4 py-3 rounded mb-6">
          <p><strong>エラー:</strong> {error}</p>
          <p className="text-sm">Supabaseの接続情報を確認してください</p>
        </div>
      )}
      
      <div className="mb-8">
        <h2 className="text-2xl font-semibold mb-4">研究同意データ ({consentData.length}件)</h2>
        {consentData.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full bg-white border border-gray-200">
              <thead>
                <tr className="bg-gray-100">
                  <th className="py-2 px-4 border">ID</th>
                  <th className="py-2 px-4 border">同意状態</th>
                  <th className="py-2 px-4 border">同意バージョン</th>
                  <th className="py-2 px-4 border">研究ID</th>
                  <th className="py-2 px-4 border">作成日時</th>
                </tr>
              </thead>
              <tbody>
                {consentData.map((record) => (
                  <tr key={record.id} className="hover:bg-gray-50">
                    <td className="py-2 px-4 border">{record.user_id}</td>
                    <td className="py-2 px-4 border">{record.consent_given ? '同意' : '不同意'}</td>
                    <td className="py-2 px-4 border">{record.consent_version}</td>
                    <td className="py-2 px-4 border">{record.study_id}</td>
                    <td className="py-2 px-4 border">{new Date(record.created_at).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-gray-600">同意データはまだありません</p>
        )}
      </div>
      
      <div>
        <h2 className="text-2xl font-semibold mb-4">人口統計データ ({demographicData.length}件)</h2>
        {demographicData.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="min-w-full bg-white border border-gray-200">
              <thead>
                <tr className="bg-gray-100">
                  <th className="py-2 px-4 border">ID</th>
                  <th className="py-2 px-4 border">年齢層</th>
                  <th className="py-2 px-4 border">性別</th>
                  <th className="py-2 px-4 border">民族</th>
                  <th className="py-2 px-4 border">収入</th>
                  <th className="py-2 px-4 border">作成日時</th>
                </tr>
              </thead>
              <tbody>
                {demographicData.map((record) => (
                  <tr key={record.id} className="hover:bg-gray-50">
                    <td className="py-2 px-4 border">{record.user_id}</td>
                    <td className="py-2 px-4 border">{record.age_group}</td>
                    <td className="py-2 px-4 border">{record.gender}</td>
                    <td className="py-2 px-4 border">{record.ethnicity}</td>
                    <td className="py-2 px-4 border">{record.income}</td>
                    <td className="py-2 px-4 border">{new Date(record.created_at).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="text-gray-600">人口統計データはまだありません</p>
        )}
      </div>
    </div>
  )
} 