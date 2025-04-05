"use client";

import { useState, useEffect } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { AlertCircle } from "lucide-react";
import FaceEmotionAnalysis from "./components/FaceEmotionAnalysis";

export default function HumeWebsocketPage() {
  const [isDebugOpen, setIsDebugOpen] = useState<boolean>(false);
  const [apiKeyInfo, setApiKeyInfo] = useState<string>("Loading...");

  useEffect(() => {
    // APIキー情報のチェック
    const apiKey = process.env.NEXT_PUBLIC_HUME_API_KEY;
    setApiKeyInfo(apiKey 
      ? `存在します (${apiKey.substring(0, 5)}...${apiKey.substring(apiKey.length - 5)})` 
      : "設定されていません。.env.localファイルを確認してください。");
  }, []);

  return (
    <div className="container mx-auto py-10">
      <h1 className="text-3xl font-bold mb-6">Hume AI 顔表情分析 WebSocket デモ</h1>
      <p className="mb-8 text-lg">
        Hume AI のWebSocketを使ったリアルタイム顔表情分析デモです。カメラを起動して表情を分析できます。
      </p>

      {/* デバッグ情報 */}
      <Collapsible
        open={isDebugOpen}
        onOpenChange={setIsDebugOpen}
        className="mb-6 bg-amber-50 border border-amber-200 rounded-md p-4"
      >
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <AlertCircle className="h-5 w-5 text-amber-500" />
            <h3 className="text-sm font-medium">接続できない場合はここをクリックしてデバッグ情報を確認</h3>
          </div>
          <CollapsibleTrigger asChild>
            <Button variant="ghost" size="sm">
              {isDebugOpen ? "閉じる" : "開く"}
            </Button>
          </CollapsibleTrigger>
        </div>
        
        <CollapsibleContent className="mt-4 space-y-4">
          <div className="space-y-2">
            <h4 className="text-sm font-medium">Hume API キー:</h4>
            <p className="text-sm">{apiKeyInfo}</p>
          </div>
          
          <div className="space-y-2">
            <h4 className="text-sm font-medium">WebSocket接続テスト:</h4>
            <p className="text-sm">接続ボタンをクリックすると、ブラウザのコンソールにデバッグ情報が表示されます。</p>
            <p className="text-sm">ブラウザの開発者ツールを開き (F12キー)、Consoleタブでエラーを確認してください。</p>
          </div>
          
          <div className="space-y-2">
            <h4 className="text-sm font-medium">一般的な問題:</h4>
            <ul className="text-sm list-disc list-inside">
              <li>APIキーが正しく設定されていない</li>
              <li>ブラウザのセキュリティ設定によりWebSocketが制限されている</li>
              <li>ファイアウォールでwss://api.hume.aiが制限されている</li>
              <li>Hume APIサービスに一時的な障害がある</li>
            </ul>
          </div>
        </CollapsibleContent>
      </Collapsible>

      <Card>
        <CardHeader>
          <CardTitle>顔表情分析</CardTitle>
          <CardDescription>カメラを使ってリアルタイムに表情を分析します</CardDescription>
        </CardHeader>
        <CardContent>
          <FaceEmotionAnalysis />
        </CardContent>
      </Card>
    </div>
  );
} 