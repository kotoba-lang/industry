"use client";

import React, { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Label } from '@/components/ui/label';
import { saveConsentData } from '@/lib/actions/consent-actions';

// 型定義
export interface DemographicData {
  ageGroup: string;
  gender: string;
  ethnicity: string;
  income: string;
}

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
    ageGroup: "",
    gender: "",
    ethnicity: "",
    income: ""
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
        await saveConsentData(
          demographicData,
          {
            consentGiven: consented,
            consentVersion,
            consentText: "Research Participation Consent for Spirit in Physics (Jung's Word Association Embedding Test)"
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
    <div className="max-w-3xl mx-auto p-6 bg-white rounded-lg shadow-md border border-gray-200">
      <h2 className="text-2xl font-bold mb-4 text-black">Research Participation Consent</h2>
      
      <div className="mb-6">
        <p className="mb-4 text-gray-800">This Spirit in Physics (Jung's Word Association Embedding Test) is conducted for research purposes. Please read the following consent information before proceeding.</p>
        
        <button 
          onClick={() => setShowFullConsent(!showFullConsent)}
          className="text-blue-600 hover:underline mb-4"
        >
          {showFullConsent ? 'Collapse Consent Form' : 'Read Full Consent Form'}
        </button>
        
        {showFullConsent && (
          <div className="p-4 bg-gray-50 rounded-md mb-4 max-h-96 overflow-y-auto text-sm">
            <h3 className="font-bold mb-2 text-gray-900">Research Purpose</h3>
            <p className="mb-4 text-gray-800">
              This research aims to investigate the relationship between language responses and psychological processes using Spirit in Physics (Jung's Word Association Embedding Test).
              In this test, we record your immediate reactions to stimulus words.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Research Procedure</h3>
            <p className="mb-4 text-gray-800">
              In this research, a series of words will be presented, and you will be asked to respond with the first word that comes to mind for each.
              Reaction times will also be recorded. The test takes approximately 15-20 minutes to complete.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Potential Risks and Discomfort</h3>
            <p className="mb-4 text-gray-800">
              There are no physical risks associated with participating in this research. However, some stimulus words may evoke personal emotions or memories.
              If you feel uncomfortable, you may discontinue the test at any time.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Benefits</h3>
            <p className="mb-4 text-gray-800">
              There may be no direct benefits from participating in this research, but the test results may help deepen your self-understanding.
              Additionally, you will be contributing to the advancement of psychological research.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Confidentiality</h3>
            <p className="mb-4 text-gray-800">
              All data collected will be anonymized and strictly protected. Your personal information will not be identified when the research results are published.
              Data will be stored on secure servers and will not be used for purposes other than research.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Voluntary Participation</h3>
            <p className="mb-4 text-gray-800">
              Participation in this research is completely voluntary. You may withdraw at any time without explaining your reasons.
              Refusing or discontinuing participation will not result in any disadvantages.
            </p>
            
            <h3 className="font-bold mb-2 text-gray-900">Contact Information</h3>
            <p className="mb-4 text-gray-800">
              If you have questions or concerns about this research, please contact the research supervisor (contact@research-example.com).
              For questions about your rights as a research participant, you may contact the ethics committee (ethics@research-example.com).
            </p>
          </div>
        )}
      </div>
      
      <div className="mb-6 p-4 bg-gray-50 rounded-md">
        <h3 className="font-bold mb-4 text-gray-900">Demographic Information (CDISC Standards)</h3>
        <p className="text-sm mb-4 text-gray-800">This information helps us understand our research participants better. All responses are anonymous and optional.</p>
        
        <div className="space-y-6">
          <div>
            <Label htmlFor="ageGroup" className="block mb-2 text-gray-800">Age Group</Label>
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
            <Label className="block mb-2 text-gray-800">Gender</Label>
            <RadioGroup 
              value={demographicData.gender} 
              onValueChange={(value: string) => handleDemographicChange("gender", value)}
              className="flex flex-col space-y-2"
            >
              <div className="flex items-center space-x-2">
                <RadioGroupItem value="male" id="gender-male" />
                <Label htmlFor="gender-male" className="text-gray-800">Male</Label>
              </div>
              <div className="flex items-center space-x-2">
                <RadioGroupItem value="female" id="gender-female" />
                <Label htmlFor="gender-female" className="text-gray-800">Female</Label>
              </div>
              <div className="flex items-center space-x-2">
                <RadioGroupItem value="non-binary" id="gender-non-binary" />
                <Label htmlFor="gender-non-binary" className="text-gray-800">Non-binary</Label>
              </div>
              <div className="flex items-center space-x-2">
                <RadioGroupItem value="prefer-not-to-say" id="gender-not-say" />
                <Label htmlFor="gender-not-say" className="text-gray-800">Prefer not to say</Label>
              </div>
            </RadioGroup>
          </div>
          
          <div>
            <Label htmlFor="ethnicity" className="block mb-2 text-gray-800">Race/Ethnicity</Label>
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
            <Label htmlFor="income" className="block mb-2 text-gray-800">Annual Income</Label>
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
      
      <div className="space-y-4 mb-6">
        <div className="flex items-start">
          <Checkbox 
            id="consent1" 
            checked={consented} 
            onCheckedChange={(checked: boolean) => setConsented(checked === true)} 
            className="mt-1"
          />
          <label htmlFor="consent1" className="ml-2 text-sm text-gray-800">
            I have read and understood the above information. I have had the opportunity to ask questions and have received satisfactory answers to my questions. I voluntarily agree to participate in this research. I understand that I have the right to withdraw at any time.
          </label>
        </div>
      </div>
      
      <div className="flex justify-end">
        <Button 
          onClick={handleSubmit} 
          disabled={!consented || isSubmitting}
          className="px-6"
        >
          {isSubmitting ? 'Processing...' : 'Consent and Continue'}
        </Button>
      </div>
      
      <div className="mt-4 text-xs text-gray-500">
        <p>This consent process complies with ICH-GCP (International Conference on Harmonisation - Good Clinical Practice) standards.</p>
        <p>Approval number: Niigata University 2025-03 | Approval date: Marth 1, 2025</p>
      </div>
    </div>
  );
} 