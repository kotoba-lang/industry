'use client';

import React, { useState } from 'react';
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import ResearchPlanContent from './ResearchPlanContent';

const ConsentForm = ({ onConsent, participantId }: { onConsent: (participantId: string, signature: string, agreements: any) => void, participantId: string }) => {
  const [agreements, setAgreements] = useState({
    understand: false,
    voluntary: false,
    withdraw: false,
    recording: false,
  });
  const [signature, setSignature] = useState('');

  const handleAgreementChange = (name: keyof typeof agreements) => (checked: boolean) => {
    setAgreements((prev) => ({ ...prev, [name]: checked }));
  };

  const isAllAgreed = Object.values(agreements).every(Boolean) && signature.trim() !== '';

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    if (isAllAgreed) {
      onConsent(participantId, signature, agreements);
    }
  };

  return (
    <Card className="w-full max-w-4xl mx-auto">
      <CardHeader>
        <CardTitle>研究参加への同意</CardTitle>
        <CardDescription>研究計画書をよくお読みの上、各項目に同意いただけましたら署名をお願いします。</CardDescription>
      </CardHeader>
      <CardContent>
        <div className="border rounded-md p-4 max-h-[50vh] overflow-y-auto mb-6 bg-gray-50/50 dark:bg-gray-900/50">
          <ResearchPlanContent />
        </div>
        <form onSubmit={handleSubmit} id="consent-form">
          <div className="space-y-4">
            <div className="flex items-start space-x-3 cursor-pointer">
              <Checkbox id="understand" checked={agreements.understand} onCheckedChange={handleAgreementChange('understand')} className="mt-1" />
              <Label htmlFor="understand" className="flex-1 cursor-pointer">研究の性質と目的を理解しました。</Label>
            </div>  
            <div className="flex items-start space-x-3 cursor-pointer">
              <Checkbox id="voluntary" checked={agreements.voluntary} onCheckedChange={handleAgreementChange('voluntary')} className="mt-1" />
              <Label htmlFor="voluntary" className="flex-1 cursor-pointer">自身の自由意思に基づき、研究に任意で参加することに同意します。</Label>
            </div>
            <div className="flex items-start space-x-3 cursor-pointer">
              <Checkbox id="withdraw" checked={agreements.withdraw} onCheckedChange={handleAgreementChange('withdraw')} className="mt-1" />
              <Label htmlFor="withdraw" className="flex-1 cursor-pointer">いつでも同意を撤回し、研究への参加を中止できることを理解しました。</Label>
            </div>
            <div className="flex items-start space-x-3 cursor-pointer">
              <Checkbox id="recording" checked={agreements.recording} onCheckedChange={handleAgreementChange('recording')} className="mt-1" />
              <Label htmlFor="recording" className="flex-1 cursor-pointer">実験中の音声および映像の記録に同意します。</Label>
            </div>
          </div>
          <div className="mt-8">
            <Label htmlFor="signature" className="font-bold text-lg">電子署名</Label>
            <Input
              type="text"
              id="signature"
              value={signature}
              onChange={(e) => setSignature(e.target.value)}
              placeholder="氏名を入力してください"
              className="mt-2 text-base p-3"
            />
          </div>
        </form>
      </CardContent>
      <CardFooter>
        <Button
          type="submit"
          form="consent-form"
          disabled={!isAllAgreed}
          className="w-full text-lg py-6"
        >
          同意して実験を開始する
        </Button>
      </CardFooter>
    </Card>
  );
};

export default ConsentForm; 