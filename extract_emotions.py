#!/usr/bin/env python3
"""
Hume APIを使用して.webmファイルから感情データを抽出するスクリプト
"""

import os
import json
import time
import requests
from pathlib import Path
from typing import Dict, List, Optional
import glob
import dotenv

class HumeEmotionExtractor:
    def __init__(self):
        # .envrcファイルから環境変数を読み込む
        envrc_path = "/Users/junkawasaki/jun784/root/.envrc"
        if os.path.exists(envrc_path):
            dotenv.load_dotenv(envrc_path)

        self.api_key = os.getenv('HUME_API_KEY')
        self.client_secret = os.getenv('HUME_CLIENT_SECRET')
        self.base_url = "https://api.hume.ai"
        self.session = requests.Session()

        if not self.api_key:
            raise ValueError("HUME_API_KEY環境変数が設定されていません")

    def authenticate(self) -> bool:
        """Hume APIで認証を設定"""
        try:
            # Hume APIの場合、X-Hume-Api-KeyヘッダーにAPIキーを設定
            self.session.headers.update({
                'X-Hume-Api-Key': self.api_key,
                'Accept': 'application/json'
            })
            print("Hume API認証を設定しました")
            return True

        except Exception as e:
            print(f"認証設定エラー: {str(e)}")
            return False

    def list_webm_files(self, directory: str) -> List[str]:
        """指定されたディレクトリ内のすべての.webmファイルをリストアップ"""
        pattern = os.path.join(directory, "**", "*.webm")
        return glob.glob(pattern, recursive=True)

    def upload_video(self, file_path: str) -> Optional[str]:
        """ビデオファイルをHume APIにアップロード"""
        try:
            # Hume Expression APIで感情分析を実行
            with open(file_path, 'rb') as f:
                file_data = f.read()

            # Base64エンコード
            import base64
            encoded_file = base64.b64encode(file_data).decode('utf-8')

            # Expression Measurement APIリクエスト
            request_data = {
                "models": {
                    "face": {
                        "fps": 1,  # 1 FPSで分析
                        "identify_faces": False
                    }
                },
                "data": encoded_file,
                "filename": os.path.basename(file_path)
            }

            response = self.session.post(
                f"{self.base_url}/v0/emotion/measurements",
                json=request_data
            )

            if response.status_code == 200:
                result_data = response.json()
                job_id = result_data.get('job_id') or f"job_{os.path.basename(file_path)}_{int(time.time())}"
                print(f"感情分析成功: {file_path} -> Job ID: {job_id}")
                return job_id
            else:
                print(f"感情分析失敗: {file_path} - Status: {response.status_code}")
                print(f"Response: {response.text}")
                return None

        except Exception as e:
            print(f"アップロードエラー: {file_path} - {str(e)}")
            return None

    def check_job_status(self, job_id: str) -> Optional[Dict]:
        """ジョブのステータスを確認（Expression APIでは使用しない）"""
        return {"state": "COMPLETED"}

    def get_job_results(self, job_id: str) -> Optional[Dict]:
        """ジョブの結果を取得（Expression APIでは使用しない）"""
        return None

    def process_video_file(self, file_path: str) -> Optional[Dict]:
        """単一のビデオファイルを処理"""
        print(f"処理開始: {file_path}")

        try:
            # ファイルサイズを確認
            file_size = os.path.getsize(file_path)
            print(f"ファイルサイズ: {file_size} bytes")

            # 大きなファイルの場合、最初の1MBだけを処理（テスト用）
            max_size = 1024 * 1024  # 1MB
            if file_size > max_size:
                print(f"ファイルが大きすぎるため、最初の{max_size}バイトだけを処理します")
                with open(file_path, 'rb') as f:
                    file_data = f.read(max_size)
            else:
                with open(file_path, 'rb') as f:
                    file_data = f.read()

            # Base64エンコード
            import base64
            encoded_file = base64.b64encode(file_data).decode('utf-8')

            # Hume Expression Measurement APIリクエスト
            request_data = {
                "data": encoded_file,
                "models": {
                    "face": {}
                },
                "filename": os.path.basename(file_path)
            }

            # Hume Batch APIを使用
            response = self.session.post(
                f"{self.base_url}/v0/batch/jobs",
                json={"models": {"face": {}}}
            )

            if response.status_code == 200:
                job_data = response.json()
                job_id = job_data.get('job_id')
                print(f"ジョブ作成成功: Job ID = {job_id}")

                # ファイルをアップロード
                upload_response = self.session.post(
                    f"{self.base_url}/v0/batch/jobs/{job_id}/artifacts",
                    json={"data": encoded_file, "filename": os.path.basename(file_path)}
                )

                if upload_response.status_code == 200:
                    print(f"ファイルアップロード成功")

                    # ジョブを開始
                    start_response = self.session.post(
                        f"{self.base_url}/v0/batch/jobs/{job_id}/start"
                    )

                    if start_response.status_code == 200:
                        print(f"ジョブ開始成功")

                        # 結果を取得（最大5分待機）
                        for attempt in range(30):
                            status_response = self.session.get(f"{self.base_url}/v0/batch/jobs/{job_id}")
                            if status_response.status_code == 200:
                                status_data = status_response.json()
                                state = status_data.get('state')
                                print(f"ジョブ状態: {state}")

                                if state == 'COMPLETED':
                                    # 結果を取得
                                    result_response = self.session.get(f"{self.base_url}/v0/batch/jobs/{job_id}/predictions")
                                    if result_response.status_code == 200:
                                        result_data = result_response.json()
                                        return {
                                            'file_path': file_path,
                                            'job_id': job_id,
                                            'results': result_data,
                                            'timestamp': time.time(),
                                            'file_size': file_size,
                                            'processed_size': len(file_data)
                                        }
                                    break
                                elif state == 'FAILED':
                                    print(f"ジョブ失敗: {job_id}")
                                    return None

                            time.sleep(10)

                        print(f"ジョブタイムアウト: {job_id}")
                        return None
                    else:
                        print(f"ジョブ開始失敗: {start_response.status_code}")
                        return None
                else:
                    print(f"ファイルアップロード失敗: {upload_response.status_code}")
                    print(f"Response: {upload_response.text}")
                    return None
            else:
                print(f"ジョブ作成失敗: {response.status_code}")
                print(f"Response: {response.text}")
                return None

        except Exception as e:
            print(f"処理エラー: {file_path} - {str(e)}")
            return None

    def process_directory(self, directory: str, output_file: str = None) -> List[Dict]:
        """ディレクトリ内のすべての.webmファイルを処理（エラーが出たら停止）"""
        webm_files = self.list_webm_files(directory)
        print(f"見つかった.webmファイル: {len(webm_files)}個")

        results = []

        for i, file_path in enumerate(webm_files):
            print(f"\n=== ファイル {i+1}/{len(webm_files)} を処理中 ===")
            result = self.process_video_file(file_path)

            if result:
                results.append(result)
                print(f"✓ 成功: {os.path.basename(file_path)}")
            else:
                print(f"✗ 失敗: {os.path.basename(file_path)}")
                print("エラーが発生したため、処理を停止します。")
                break

            # APIレート制限を考慮して少し待機
            time.sleep(2)

        # 結果を保存
        if output_file and results:
            self.save_results(results, output_file)
            print(f"結果を保存しました: {output_file}")

        return results

    def save_results(self, results: List[Dict], output_file: str):
        """結果をJSONファイルに保存"""
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(results, f, indent=2, ensure_ascii=False)

def main():
    """メイン関数"""
    try:
        # 環境変数の読み込み
        extractor = HumeEmotionExtractor()

        # 認証
        if not extractor.authenticate():
            print("認証に失敗しました。APIキーを確認してください。")
            return

        # 対象ディレクトリ
        target_dir = "/Users/junkawasaki/jun784/root/apps/spirit-in-physics/v250730/.artifacts_cache"

        # 出力ファイル
        output_file = "/Users/junkawasaki/jun784/root/emotion_results.json"

        print("感情データ抽出を開始します...")
        print(f"対象ディレクトリ: {target_dir}")

        # 処理実行
        results = extractor.process_directory(target_dir, output_file)

        print(f"処理完了: {len(results)}個のファイルを正常に処理しました")

        # 結果のサマリーを表示
        for result in results:
            file_path = result['file_path']
            job_id = result['job_id']
            print(f"✓ {os.path.basename(file_path)} (Job ID: {job_id})")

    except ValueError as e:
        print(f"設定エラー: {str(e)}")
    except Exception as e:
        print(f"予期しないエラー: {str(e)}")

if __name__ == "__main__":
    main()

