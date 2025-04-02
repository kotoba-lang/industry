/**
 * 単語音声生成ページ
 * Hume AIを使用して単語やフレーズの音声を生成し、ローカルに保存します
 */
'use client';

import React, { useState, useEffect, useRef } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Label } from '@/components/ui/label';
import { AlertCircle, CheckCircle2, Trash2, Play, Pause, Upload, Download } from 'lucide-react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { generateAndSaveVoice, getVoiceFiles, deleteVoiceFile } from '@/lib/actions/hume-voice-generator';
import { JUNG_STIMULUS_WORDS } from '@/components/jung-voice-assessment/JungVoiceTest';

interface AudioItem {
  url: string;
  name: string;
  isPlaying: boolean;
}

export default function VoiceGeneratorPage() {
  // 状態管理
  const [text, setText] = useState<string>('');
  const [multilineText, setMultilineText] = useState<string>('');
  const [voiceName, setVoiceName] = useState<string>('David Hume');
  const [apiKey, setApiKey] = useState<string>(process.env.NEXT_PUBLIC_HUME_API_KEY || '');
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [audioFiles, setAudioFiles] = useState<AudioItem[]>([]);
  const [uploadedJungWords, setUploadedJungWords] = useState<boolean>(false);

  // オーディオ再生用のRef
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const currentlyPlayingRef = useRef<string | null>(null);

  // 音声ファイル一覧の取得
  const fetchAudioFiles = async () => {
    try {
      const result = await getVoiceFiles();
      if (result.success && result.files) {
        const formattedFiles = result.files.map(url => ({
          url,
          name: decodeURIComponent(url.split('/').pop() || ''),
          isPlaying: false
        }));
        setAudioFiles(formattedFiles);
      } else {
        console.error('Failed to fetch audio files:', result.error);
      }
    } catch (error) {
      console.error('Error fetching audio files:', error);
    }
  };

  // 初回ロード時にファイル一覧を取得
  useEffect(() => {
    fetchAudioFiles();
  }, []);

  // 単一テキストの音声生成
  const handleGenerateSingleVoice = async () => {
    if (!text.trim()) {
      setError('テキストを入力してください');
      return;
    }

    if (!apiKey) {
      setError('Hume API Keyを入力してください');
      return;
    }

    setIsLoading(true);
    setError(null);
    setSuccess(null);

    try {
      const result = await generateAndSaveVoice({
        text: text.trim(),
        voiceName,
        apiKey
      });

      if (result.success && result.audioUrl) {
        setSuccess(`音声が正常に生成されました: ${result.audioUrl}`);
        setText('');
        fetchAudioFiles();
      } else {
        setError(result.error || '音声生成に失敗しました');
      }
    } catch (error) {
      console.error('Error generating voice:', error);
      setError('音声生成中にエラーが発生しました');
    } finally {
      setIsLoading(false);
    }
  };

  // 複数行テキストの音声生成
  const handleGenerateMultiVoice = async () => {
    if (!multilineText.trim()) {
      setError('テキストを入力してください');
      return;
    }

    if (!apiKey) {
      setError('Hume API Keyを入力してください');
      return;
    }

    setIsLoading(true);
    setError(null);
    setSuccess(null);

    try {
      const lines = multilineText
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0);

      let successCount = 0;
      let errorCount = 0;

      // 各行を順番に処理
      for (const line of lines) {
        const result = await generateAndSaveVoice({
          text: line,
          voiceName,
          apiKey
        });

        if (result.success) {
          successCount++;
        } else {
          errorCount++;
          console.error(`Failed to generate voice for text: "${line}"`, result.error);
        }
      }

      if (successCount > 0) {
        setSuccess(`${successCount}個の音声が正常に生成されました。${errorCount > 0 ? `${errorCount}個の音声生成に失敗しました。` : ''}`);
        if (successCount === lines.length) {
          setMultilineText('');
        }
        fetchAudioFiles();
      } else {
        setError('すべての音声生成に失敗しました');
      }
    } catch (error) {
      console.error('Error generating voices:', error);
      setError('音声生成中にエラーが発生しました');
    } finally {
      setIsLoading(false);
    }
  };

  // ユングの刺激語をアップロード
  const handleUploadJungWords = async () => {
    if (!apiKey) {
      setError('Hume API Keyを入力してください');
      return;
    }

    setIsLoading(true);
    setError(null);
    setSuccess(null);

    try {
      let successCount = 0;
      let errorCount = 0;

      // 各刺激語を処理
      for (const word of JUNG_STIMULUS_WORDS) {
        const result = await generateAndSaveVoice({
          text: word,
          voiceName,
          apiKey,
          fileName: `jung_${word.replace(/\s+/g, '_').replace(/[^a-z0-9_]/gi, '')}.mp3`
        });

        if (result.success) {
          successCount++;
        } else {
          errorCount++;
          console.error(`Failed to generate voice for Jung word: "${word}"`, result.error);
        }
      }

      if (successCount > 0) {
        setSuccess(`${successCount}個のユング刺激語の音声が正常に生成されました。${errorCount > 0 ? `${errorCount}個の音声生成に失敗しました。` : ''}`);
        setUploadedJungWords(true);
        fetchAudioFiles();
      } else {
        setError('すべての音声生成に失敗しました');
      }
    } catch (error) {
      console.error('Error generating Jung word voices:', error);
      setError('音声生成中にエラーが発生しました');
    } finally {
      setIsLoading(false);
    }
  };

  // 音声ファイルの削除
  const handleDeleteAudio = async (url: string) => {
    try {
      const fileName = url.split('/').pop();
      if (!fileName) return;

      const result = await deleteVoiceFile(fileName);
      
      if (result.success) {
        setAudioFiles(prev => prev.filter(file => file.url !== url));
        setSuccess(`音声ファイルが削除されました: ${fileName}`);
      } else {
        setError(result.error || 'ファイルの削除に失敗しました');
      }
    } catch (error) {
      console.error('Error deleting audio file:', error);
      setError('ファイル削除中にエラーが発生しました');
    }
  };

  // 音声ファイルの再生
  const handlePlayAudio = (url: string) => {
    // 現在再生中の音声があれば停止
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current.src = '';
      
      // 以前再生していた音声のisPlayingフラグをリセット
      if (currentlyPlayingRef.current) {
        setAudioFiles(prev => 
          prev.map(file => 
            file.url === currentlyPlayingRef.current 
              ? { ...file, isPlaying: false } 
              : file
          )
        );
      }
    }

    // 新しい音声を再生
    audioRef.current = new Audio(url);
    currentlyPlayingRef.current = url;
    
    // 再生状態を更新
    setAudioFiles(prev => 
      prev.map(file => 
        file.url === url 
          ? { ...file, isPlaying: true } 
          : file
      )
    );
    
    // 再生終了時の処理
    audioRef.current.onended = () => {
      setAudioFiles(prev => 
        prev.map(file => 
          file.url === url 
            ? { ...file, isPlaying: false } 
            : file
        )
      );
      currentlyPlayingRef.current = null;
    };
    
    audioRef.current.play().catch(err => {
      console.error('Error playing audio:', err);
      setError('音声の再生に失敗しました');
      setAudioFiles(prev => 
        prev.map(file => 
          file.url === url 
            ? { ...file, isPlaying: false } 
            : file
        )
      );
      currentlyPlayingRef.current = null;
    });
  };

  // 音声ファイルの停止
  const handleStopAudio = (url: string) => {
    if (audioRef.current && currentlyPlayingRef.current === url) {
      audioRef.current.pause();
      audioRef.current.src = '';
      currentlyPlayingRef.current = null;
      
      setAudioFiles(prev => 
        prev.map(file => 
          file.url === url 
            ? { ...file, isPlaying: false } 
            : file
        )
      );
    }
  };

  // クリーンアップ（コンポーネントのアンマウント時）
  useEffect(() => {
    return () => {
      if (audioRef.current) {
        audioRef.current.pause();
        audioRef.current.src = '';
      }
    };
  }, []);

  return (
    <div className="container mx-auto py-8">
      <h1 className="text-3xl font-bold mb-6">Hume AI 音声生成ツール</h1>
      
      {error && (
        <Alert variant="destructive" className="mb-6">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>エラー</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      
      {success && (
        <Alert variant="default" className="mb-6 bg-green-50 border-green-200">
          <CheckCircle2 className="h-4 w-4 text-green-600" />
          <AlertTitle className="text-green-600">成功</AlertTitle>
          <AlertDescription className="text-green-700">{success}</AlertDescription>
        </Alert>
      )}
      
      <Card className="mb-8">
        <CardHeader>
          <CardTitle>API設定</CardTitle>
          <CardDescription>
            Hume AIの音声生成に必要なAPI設定を行います
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="space-y-4">
            <div>
              <Label htmlFor="apiKey">Hume AI API Key</Label>
              <Input
                id="apiKey"
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="sk_..."
                className="font-mono"
              />
              <p className="text-xs text-gray-500 mt-1">
                Hume AI APIキーがない場合は、<a href="https://hume.ai/" target="_blank" rel="noopener noreferrer" className="text-blue-600 underline">hume.ai</a> で取得してください。
              </p>
            </div>
            
            <div>
              <Label htmlFor="voiceName">音声の選択</Label>
              <Select
                value={voiceName}
                onValueChange={setVoiceName}
              >
                <SelectTrigger id="voiceName" className="w-full">
                  <SelectValue placeholder="音声を選択" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="David Hume">David Hume</SelectItem>
                  <SelectItem value="Immanuel Kant">Immanuel Kant</SelectItem>
                  <SelectItem value="Albert Einstein">Albert Einstein</SelectItem>
                  <SelectItem value="Marie Curie">Marie Curie</SelectItem>
                  <SelectItem value="Alan Turing">Alan Turing</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        </CardContent>
      </Card>
      
      <Tabs defaultValue="single" className="mb-8">
        <TabsList className="grid w-full grid-cols-2">
          <TabsTrigger value="single">単一テキスト</TabsTrigger>
          <TabsTrigger value="multi">複数テキスト</TabsTrigger>
        </TabsList>
        
        <TabsContent value="single" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>単一テキスト音声生成</CardTitle>
              <CardDescription>
                1つの単語やフレーズから音声を生成します
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <div>
                  <Label htmlFor="singleText">テキスト</Label>
                  <Input
                    id="singleText"
                    value={text}
                    onChange={(e) => setText(e.target.value)}
                    placeholder="生成する単語やフレーズを入力..."
                  />
                </div>
              </div>
            </CardContent>
            <CardFooter className="flex justify-between">
              <div></div>
              <Button 
                onClick={handleGenerateSingleVoice} 
                disabled={isLoading || !text.trim() || !apiKey}
              >
                {isLoading ? '生成中...' : '音声生成'}
              </Button>
            </CardFooter>
          </Card>
        </TabsContent>
        
        <TabsContent value="multi" className="mt-4">
          <Card>
            <CardHeader>
              <CardTitle>複数テキスト音声生成</CardTitle>
              <CardDescription>
                複数の単語やフレーズから一括で音声を生成します（1行に1つのテキスト）
              </CardDescription>
            </CardHeader>
            <CardContent>
              <div className="space-y-4">
                <div>
                  <Label htmlFor="multiText">テキスト（1行に1単語）</Label>
                  <Textarea
                    id="multiText"
                    value={multilineText}
                    onChange={(e) => setMultilineText(e.target.value)}
                    placeholder="生成する単語やフレーズを1行に1つずつ入力..."
                    rows={8}
                  />
                </div>
              </div>
            </CardContent>
            <CardFooter className="flex justify-between items-center">
              <Button 
                variant="outline" 
                onClick={handleUploadJungWords} 
                disabled={isLoading || !apiKey || uploadedJungWords}
                className="flex items-center gap-2"
              >
                <Upload className="h-4 w-4" />
                ユングの刺激語をアップロード
              </Button>
              <Button 
                onClick={handleGenerateMultiVoice} 
                disabled={isLoading || !multilineText.trim() || !apiKey}
              >
                {isLoading ? '生成中...' : '一括音声生成'}
              </Button>
            </CardFooter>
          </Card>
        </TabsContent>
      </Tabs>
      
      <Card>
        <CardHeader>
          <CardTitle>保存済み音声ファイル</CardTitle>
          <CardDescription>
            生成された音声ファイルの一覧です
          </CardDescription>
        </CardHeader>
        <CardContent>
          {audioFiles.length === 0 ? (
            <p className="text-center py-8 text-gray-500">
              保存された音声ファイルがありません
            </p>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {audioFiles.map((file, index) => (
                <div 
                  key={index}
                  className="flex items-center justify-between p-3 border rounded-lg hover:bg-gray-50"
                >
                  <div className="mr-4 flex-grow overflow-hidden">
                    <div className="text-sm font-medium truncate">{file.name}</div>
                    <div className="text-xs text-gray-500">{file.url}</div>
                  </div>
                  <div className="flex items-center space-x-2">
                    {file.isPlaying ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => handleStopAudio(file.url)}
                        className="h-8 w-8 p-0"
                      >
                        <Pause className="h-4 w-4" />
                      </Button>
                    ) : (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => handlePlayAudio(file.url)}
                        className="h-8 w-8 p-0"
                      >
                        <Play className="h-4 w-4" />
                      </Button>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => handleDeleteAudio(file.url)}
                      className="h-8 w-8 p-0 text-red-500 hover:text-red-700 hover:bg-red-50"
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                    <a 
                      href={file.url} 
                      download 
                      className="h-8 w-8 flex items-center justify-center rounded-full hover:bg-gray-100"
                    >
                      <Download className="h-4 w-4 text-gray-600" />
                    </a>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
        <CardFooter className="flex justify-between">
          <div>
            <Badge variant="outline" className="mr-2">
              合計: {audioFiles.length} ファイル
            </Badge>
          </div>
          <Button variant="outline" onClick={fetchAudioFiles}>
            更新
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
} 