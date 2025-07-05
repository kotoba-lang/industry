"use client";

import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { saveConsentAndDemographicData } from '@/lib/actions/consent-actions';
import { DemographicData } from '@/lib/db/schema/demographic';
interface ConsentFormProps {
  onConsent: (demographicData: DemographicData) => void;
  consentVersion?: string;
  studyId?: string;
}

export default function ConsentForm({ 
  onConsent, 
  consentVersion = "1.0",
  studyId = "SPIRIT-IN-PHYSICS-2025"
}: ConsentFormProps) {
  const [consented, setConsented] = useState(false);
  const [showFullConsent, setShowFullConsent] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [demographicData, setDemographicData] = useState<DemographicData>({
    ageGroup: "prefer-not-to-say",
    gender: "prefer-not-to-say",
    ethnicity: "prefer-not-to-say",
    income: "prefer-not-to-say",
    id: "",
    userId: "",
    createdAt: new Date(),
    ipAddress: null,
    userAgent: null,
    studyId: studyId,
    consentVersion: consentVersion,
  });
  
  const handleDemographicChange = (field: keyof DemographicData, value: string) => {
    setDemographicData(prev => ({
      ...prev,
      [field]: value
    }));
  };

  const handleSubmit = async () => {
    try {
      setIsSubmitting(true);

      // データベースに保存
      if (typeof window !== 'undefined') {
        await saveConsentAndDemographicData(
          demographicData,
          {
            id: "", 
            userId: "",
            createdAt: new Date(),
            ipAddress: null,
            userAgent: null,
            studyId: studyId,
            consentVersion: consentVersion,
            consentGiven: consented,
            consentText: "Research Participation Consent for Spirit in Physics (Jung's Word Association Embedding Test)",
            researcherNote: null
          },
          {
            userAgent: window.navigator.userAgent,
            studyId
          }
        );
      }

      // UIのコールバックを呼び出し
      onConsent(demographicData);
    } catch (error) {
      console.error('Error saving consent data:', error);
      // エラー処理（必要に応じてUIにエラーメッセージを表示）
    } finally {
      setIsSubmitting(false);
    }
  };
  
  return (
    <form className="w-full max-w-3xl mx-auto p-4 sm:p-5 bg-white dark:bg-gray-800 rounded-lg shadow-md border border-gray-200 dark:border-gray-700" onSubmit={handleSubmit}>
      <h2 className="text-xl sm:text-2xl font-bold mb-3 text-black dark:text-white">Research Participation Consent</h2>
      
      <div className="mb-4 sm:mb-5">
        <p className="mb-3 text-sm sm:text-base text-gray-800 dark:text-gray-200">This Spirit in Physics (Jung's Word Association Embedding Test) is conducted for research purposes. Please read the following consent information before proceeding.</p>
        
        <button 
          onClick={() => setShowFullConsent(!showFullConsent)}
          className="text-blue-600 dark:text-blue-400 hover:underline mb-3 text-sm sm:text-base"
        >
          {showFullConsent ? 'Collapse Consent Form' : 'Read Full Consent Form'}
        </button>
        
        {showFullConsent && (
          <div className="p-3 sm:p-4 bg-gray-50 dark:bg-gray-700 rounded-md mb-3 max-h-72 sm:max-h-96 overflow-y-auto text-xs sm:text-sm">
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Research Purpose</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              This research aims to investigate the relationship between language responses and psychological processes using Spirit in Physics (Jung's Word Association Embedding Test).
              In this test, we record your immediate reactions to stimulus words.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Research Procedure</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              In this research, a series of words will be presented, and you will be asked to respond with the first word that comes to mind for each.
              Reaction times will also be recorded. The test takes approximately 15-20 minutes to complete.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Potential Risks and Discomfort</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              There are no physical risks associated with participating in this research. However, some stimulus words may evoke personal emotions or memories.
              If you feel uncomfortable, you may discontinue the test at any time.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Benefits</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              There may be no direct benefits from participating in this research, but the test results may help deepen your self-understanding.
              Additionally, you will be contributing to the advancement of psychological research.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Confidentiality</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              All data collected will be anonymized and strictly protected. Your personal information will not be identified when the research results are published.
              Data will be stored on secure servers and will not be used for purposes other than research.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Voluntary Participation</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              Participation in this research is completely voluntary. You may withdraw at any time without explaining your reasons.
              Refusing or discontinuing participation will not result in any disadvantages.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900 dark:text-gray-100">Contact Information</h3>
            <p className="mb-3 text-gray-800 dark:text-gray-200">
              If you have questions or concerns about this research, please contact the research supervisor (contact@research-example.com).
              For questions about your rights as a research participant, you may contact the ethics committee (ethics@research-example.com).
            </p>
          </div>
        )}
      </div>
      
      <div className="mb-4 sm:mb-5 p-3 sm:p-4 bg-gray-50 dark:bg-gray-700 rounded-md">
        <h3 className="font-bold mb-3 text-gray-900 dark:text-gray-100 text-sm sm:text-base">Demographic Information (CDISC Standards)</h3>
        <p className="text-xs sm:text-sm mb-3 text-gray-800 dark:text-gray-200">This information helps us understand our research participants better. All responses are anonymous and optional.</p>
        
        <div className="space-y-4 sm:space-y-5">
          <div>
            <Label htmlFor="ageGroup" className="block mb-2 text-xs sm:text-sm text-gray-800 dark:text-gray-200">Age Group</Label>
            <Select 
              value={demographicData.ageGroup} 
              onValueChange={(value: string) => handleDemographicChange("ageGroup", value)}
            >
              <SelectTrigger id="ageGroup" className="w-full">
                <SelectValue placeholder="Select age group" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="prefer-not-to-say">Prefer not to say</SelectItem>
                <SelectItem value="18-24">18-24</SelectItem>
                <SelectItem value="25-34">25-34</SelectItem>
                <SelectItem value="35-44">35-44</SelectItem>
                <SelectItem value="45-54">45-54</SelectItem>
                <SelectItem value="55-64">55-64</SelectItem>
                <SelectItem value="65+">65+</SelectItem>
              </SelectContent>
            </Select>
          </div>
          
          <div>
            <Label className="block mb-2 text-xs sm:text-sm text-gray-800 dark:text-gray-200">Gender</Label>
            <div className="flex flex-col space-y-2">
              {[
                { value: "male", label: "Male" },
                { value: "female", label: "Female" }, 
                { value: "non-binary", label: "Non-binary" },
                { value: "prefer-not-to-say", label: "Prefer not to say" }
              ].map((option) => (
                <div 
                  key={option.value} 
                  className={`
                    border rounded-md p-2 cursor-pointer transition-all
                    ${demographicData.gender === option.value 
                      ? 'bg-blue-50 dark:bg-blue-900/30 border-blue-500 shadow-sm' 
                      : 'hover:bg-gray-50 dark:hover:bg-gray-700 border-gray-200 dark:border-gray-600'}
                  `}
                  onClick={() => handleDemographicChange("gender", option.value)}
                >
                  <div className="flex items-center gap-2">
                    <input 
                      type="radio" 
                      id={`gender-${option.value}`}
                      name="gender"
                      value={option.value}
                      checked={demographicData.gender === option.value}
                      onChange={() => {}}
                      className="h-4 w-4"
                      aria-label={`Gender: ${option.label}`}
                    />
                    <Label 
                      htmlFor={`gender-${option.value}`} 
                      className="text-xs sm:text-sm text-gray-800 dark:text-gray-200 cursor-pointer flex-1"
                    >
                      {option.label}
                    </Label>
                  </div>
                </div>
              ))}
            </div>
          </div>
          
          <div>
            <Label htmlFor="ethnicity" className="block mb-2 text-xs sm:text-sm text-gray-800 dark:text-gray-200">Race/Ethnicity</Label>
            <Select 
              value={demographicData.ethnicity} 
              onValueChange={(value: string) => handleDemographicChange("ethnicity", value)}
            >
              <SelectTrigger id="ethnicity" className="w-full">
                <SelectValue placeholder="Select ethnicity" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="prefer-not-to-say">Prefer not to say</SelectItem>
                <SelectItem value="asian">Asian</SelectItem>
                <SelectItem value="black">Black or African American</SelectItem>
                <SelectItem value="hispanic">Hispanic or Latino</SelectItem>
                <SelectItem value="native">Native American or Alaska Native</SelectItem>
                <SelectItem value="pacific">Native Hawaiian or Pacific Islander</SelectItem>
                <SelectItem value="white">White</SelectItem>
                <SelectItem value="multiple">Multiple ethnicities</SelectItem>
                <SelectItem value="other">Other</SelectItem>
              </SelectContent>
            </Select>
          </div>
          
          <div>
            <Label htmlFor="income" className="block mb-2 text-xs sm:text-sm text-gray-800 dark:text-gray-200">Annual Income</Label>
            <Select 
              value={demographicData.income} 
              onValueChange={(value: string) => handleDemographicChange("income", value)}
            >
              <SelectTrigger id="income" className="w-full">
                <SelectValue placeholder="Select income range" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="prefer-not-to-say">Prefer not to say</SelectItem>
                <SelectItem value="under-25k">Under $25,000</SelectItem>
                <SelectItem value="25k-50k">$25,000 - $50,000</SelectItem>
                <SelectItem value="50k-75k">$50,000 - $75,000</SelectItem>
                <SelectItem value="75k-100k">$75,000 - $100,000</SelectItem>
                <SelectItem value="100k-150k">$100,000 - $150,000</SelectItem>
                <SelectItem value="over-150k">Over $150,000</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>
      
      <div className="space-y-3 mb-4">
        <div className="flex items-start">
          <Checkbox 
            id="consent1" 
            checked={consented} 
            onCheckedChange={(checked: boolean) => setConsented(checked === true)} 
            className="mt-1 h-4 w-4"
          />
          <label htmlFor="consent1" className="ml-2 text-xs sm:text-sm text-gray-800 dark:text-gray-200">
            I have read and understood the above information. I have had the opportunity to ask questions and have received satisfactory answers to my questions. I voluntarily agree to participate in this research. I understand that I have the right to withdraw at any time.
          </label>
        </div>
      </div>
      
      <div className="flex justify-end">
        <Button 
          onClick={handleSubmit} 
          disabled={!consented || isSubmitting}
          className="px-4 py-2 text-sm"
        >
          {isSubmitting ? 'Processing...' : 'Consent and Continue'}
        </Button>
      </div>
      
      <div className="mt-3 text-xs text-gray-500 dark:text-gray-400">
        <p>This consent process complies with ICH-GCP (International Conference on Harmonisation - Good Clinical Practice) standards.</p>
        <p>IRB Approval number: Niigata University 2024-0269 | Approval date: Marth 1, 2025</p>
      </div>
    </form>
  );
} 