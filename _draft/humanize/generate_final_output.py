#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
最終アウトプット生成スクリプト
ai-text.mdの全文を人間化処理してhumanized-ai-text.mdを生成
"""

import re
import random
import numpy as np
from typing import List, Dict, Tuple

class FinalHumanizationPipeline:
    """最終版人間化処理パイプライン"""
    
    def __init__(self):
        # 同義語マッピング（最終版）
        self.synonym_mapping = {
            "明らかにした": ["解明した", "突き止めた", "判明した", "発見した", "特定した", "実証した"],
            "示した": ["実証した", "証明した", "立証した", "提示した", "表明した", "確認した"],
            "可能になった": ["実現した", "達成された", "叶った", "可能となった", "実現可能となった"],
            "重要である": ["肝要である", "重大である", "核心的である", "本質的である", "不可欠である"],
            "発見された": ["見出された", "確認された", "観察された", "検出された", "特定された"],
            "技術": ["テクノロジー", "手法", "方法論", "アプローチ", "技法"],
            "解析": ["分析", "調査", "検討", "評価", "検証", "検査"],
            "研究": ["調査", "探究", "検討", "研究活動", "学術的探求", "検証", "学術研究"],
            "臓器": ["器官", "組織", "生体器官", "生体組織"],
            "システム": ["体系", "機構", "ネットワーク", "システム構造", "メカニズム"],
            "明らかになりつつある": ["判明しつつある", "解明されつつある", "見えてきている", "明確になりつつある"],
            "用いられている": ["使用されている", "活用されている", "利用されている", "採用されている", "応用されている"],
            "困難であった": ["難しかった", "困難を極めた", "チャレンジングであった", "複雑であった"],
            "次々と": ["相次いで", "続々と", "立て続けに", "連続して", "次から次へと"],
            "開発した": ["構築した", "創出した", "作成した", "設計した", "実現した", "開発を行った"],
            "応用": ["適用", "活用", "利用", "運用", "展開"],
            "機能": ["働き", "役割", "作用", "機能性", "効果"],
            "方法": ["手法", "アプローチ", "技法", "方式", "手段"],
            "結果": ["成果", "結論", "所見", "知見", "発見"],
            "により": ["によって", "を通じて", "を介して", "を用いて"],
            "において": ["では", "にて", "の中で", "の場合"],
            "さらに": ["加えて", "また", "なお", "さらには", "その上"],
            "特に": ["とりわけ", "なかでも", "特別に", "中でも"],
            "これらの": ["これらすべての", "上記の", "こうした", "このような"],
            "複数の": ["多数の", "複数種類の", "各種の", "様々な"],
            "新たな": ["新しい", "革新的な", "画期的な", "斬新な"],
            "基づいて": ["基づき", "踏まえて", "依拠して", "もとづいて"],
            "対して": ["に対し", "について", "関して", "向けて"],
            "通じて": ["を介して", "により", "経由して", "によって"],
            "関連": ["関係", "結びつき", "つながり", "関わり"],
            "重要": ["重大", "肝要", "核心的", "本質的"],
            "効果": ["効能", "作用", "影響", "機能"],
            "構造": ["構成", "組織", "仕組み", "体制"],
            "活動": ["動作", "働き", "機能", "作用"],
            "情報": ["データ", "知識", "情報", "情報内容"],
            "理解": ["把握", "認識", "理解", "理解度"],
            "変化": ["変動", "変移", "変更", "変化"],
            "影響": ["作用", "効果", "影響", "効果"],
            "評価": ["検討", "解析", "分析", "査定", "判定"],
            "検討": ["検証", "考察", "評価", "分析"],
            "分析": ["解析", "検討", "調査", "評価"]
        }
        
        # 専門用語の言い換え（最終版）
        self.technical_variations = {
            "臓器インタラクトミクス": ["臓器間相互作用学", "器官間ネットワーク", "臓器連携システム", "臓器間コミュニケーション", "器官間相互作用"],
            "3Dイメージング": ["三次元画像化", "立体視覚化", "空間画像構築", "三次元可視化", "立体構造解析"],
            "組織透明化": ["組織クリアリング", "透明化処理", "組織透明化技術", "透明化手法"],
            "神経回路": ["神経ネットワーク", "神経経路", "ニューロン回路", "神経系統", "神経結合"],
            "可視化": ["視覚化", "画像化", "描出", "可視的表現", "映像化"],
            "恒常性": ["ホメオスタシス", "平衡状態", "安定性維持", "恒常性維持"],
            "レジリエンス": ["回復力", "復元力", "耐性", "適応力"],
            "マッピング": ["地図作成", "マップ化", "位置特定", "配置解析"],
            "トレーシング": ["追跡", "経路追跡", "トレース", "標識追跡"],
            "標識": ["ラベル", "マーカー", "目印", "識別子"],
            "イメージング": ["画像化", "映像化", "撮像", "画像取得"],
            "解明": ["発見", "特定", "判明", "解析", "確認"],
            "統合": ["結合", "融合", "一体化", "統一", "合成"],
            "制御": ["調節", "コントロール", "管理", "操作"],
            "ネットワーク": ["網羅", "ネットワーク構造", "結合網", "連結系"],
            "プロファイル": ["プロフィール", "特性", "特徴", "パターン"],
            "アーキテクチャ": ["構造", "設計", "体系", "枠組み"],
            "モデル": ["モデル系", "実験系", "実験モデル", "研究モデル"],
            "パイプライン": ["処理系", "処理経路", "解析系", "処理手順"],
            "プロトコール": ["手順", "方法", "プロセス", "手法"],
            "ベクター": ["ベクトル", "運搬体", "導入体", "輸送体"],
            "クラスター": ["群", "集団", "クラスタ", "集合体"],
            "シグナル": ["信号", "シグナル", "情報", "合図"],
            "レセプター": ["受容体", "レセプタ", "受容器", "受容分子"],
            "ニューロン": ["神経細胞", "ニューロン", "神経要素", "神経単位"],
            "パラメータ": ["パラメーター", "指標", "変数", "因子"],
            "データ": ["データセット", "情報", "資料", "データ群"],
            "サンプル": ["標本", "試料", "サンプル", "検体"],
            "ライブラリ": ["ライブラリー", "収集", "蓄積", "保管"]
        }
        
        # 文章装飾パターン（最終版）
        self.hedging_patterns = [
            "興味深いことに、{}",
            "注目すべきは、{}",
            "驚くべきことに、{}",
            "重要なことは、{}",
            "特筆すべきは、{}",
            "実際のところ、{}",
            "詳しく見ると、{}",
            "さらに言えば、{}",
            "ここで重要なのは、{}",
            "興味深い点として、{}",
            "特に注目すべきは、{}",
            "最も重要なことは、{}",
            "この点で興味深いのは、{}",
            "特に興味深いのは、{}",
            "ここで特筆すべきは、{}",
            "この研究で注目すべきは、{}",
            "重要な発見として、{}",
            "特に意義深いのは、{}"
        ]
        
        # 個人的視点マーカー（最終版）
        self.personal_markers = [
            "筆者らの観点では", "我々の研究では", "この研究において", "本研究の結果として",
            "研究者らによると", "本論文では", "著者らは", "研究チームは", "本研究では",
            "我々の検討では", "筆者らの分析では", "本研究グループは", "研究者らの見解では",
            "著者らの検討では", "研究グループの分析では", "本研究チームの見解では"
        ]
        
        # 接続表現の多様化（最終版）
        self.connective_variations = {
            "また": ["さらに", "加えて", "なお", "その上", "さらには"],
            "しかし": ["ところが", "だが", "けれども", "一方で", "ただし"],
            "さらに": ["また", "加えて", "なお", "その上", "さらには"],
            "そのため": ["従って", "このため", "よって", "したがって", "それゆえ"],
            "このように": ["このような形で", "かくして", "こうして", "このようにして"],
            "一方": ["他方", "これに対し", "逆に", "反対に"],
            "例えば": ["具体的には", "たとえば", "実例として", "具体例として"],
            "つまり": ["すなわち", "要するに", "言い換えれば", "換言すれば"],
            "実際": ["現実に", "事実", "実のところ", "実際に"],
            "当然": ["もちろん", "言うまでもなく", "自然に", "当然ながら"]
        }
        
        # 学術的表現の多様化
        self.academic_expressions = {
            "明らかにした": ["解明に成功した", "突き止めることができた", "特定することに成功した"],
            "示している": ["証明している", "立証している", "実証している"],
            "考えられる": ["推測される", "想定される", "予想される"],
            "報告されている": ["報告がなされている", "報告例がある", "報告がある"],
            "知られている": ["認知されている", "周知されている", "確認されている"],
            "理解されている": ["理解が進んでいる", "理解が深まっている", "認識されている"]
        }
        
    def transform_document(self, text: str) -> str:
        """文書全体を人間化する"""
        # 全体の前処理
        text = self._preprocess_document(text)
        
        # 段落ごとに処理
        paragraphs = self._split_into_paragraphs(text)
        processed_paragraphs = []
        
        for i, paragraph in enumerate(paragraphs):
            if paragraph.strip():
                # 段落の人間化処理
                processed = self._transform_paragraph(paragraph, i)
                processed_paragraphs.append(processed)
            else:
                processed_paragraphs.append(paragraph)
        
        # 全体の後処理
        result = '\n'.join(processed_paragraphs)
        result = self._postprocess_document(result)
        
        return result
    
    def _preprocess_document(self, text: str) -> str:
        """文書の前処理"""
        # 改行の正規化
        text = text.replace('\r\n', '\n').replace('\r', '\n')
        
        # 連続する空白行を整理
        text = re.sub(r'\n\s*\n\s*\n', '\n\n', text)
        
        return text
    
    def _split_into_paragraphs(self, text: str) -> List[str]:
        """段落に分割"""
        return text.split('\n')
    
    def _transform_paragraph(self, paragraph: str, index: int) -> str:
        """段落を人間化する"""
        # タイトルや見出しの場合は軽微な変更のみ
        if self._is_title_or_header(paragraph):
            return self._transform_title(paragraph)
        
        # 参考文献の場合はそのまま
        if self._is_reference(paragraph):
            return paragraph
        
        # 本文の場合は完全な人間化処理
        if len(paragraph.strip()) > 20:
            return self._transform_content(paragraph, index)
        
        return paragraph
    
    def _is_title_or_header(self, text: str) -> bool:
        """タイトルや見出しかどうかを判定"""
        text = text.strip()
        
        # 短い文章で、特定の条件を満たす場合
        if len(text) < 100:
            # 数字で始まる（章番号など）
            if re.match(r'^\d+\.', text):
                return True
            # 英語のタイトル
            if re.match(r'^[A-Za-z\s]+$', text):
                return True
            # 著者名のパターン
            if '（' in text and '）' in text and len(text) < 50:
                return True
        
        return False
    
    def _is_reference(self, text: str) -> bool:
        """参考文献かどうかを判定"""
        text = text.strip()
        
        # 数字で始まって著者名やジャーナル名が含まれる
        if re.match(r'^\d+\.', text) and ('et al' in text or 'Cell' in text or 'Nature' in text or 'Science' in text):
            return True
        
        # 「この文書は」で始まる特殊な文
        if text.startswith('この文書は'):
            return True
        
        return False
    
    def _transform_title(self, title: str) -> str:
        """タイトルの軽微な変更"""
        # 専門用語の一部のみ変更
        for original, variations in self.technical_variations.items():
            if original in title and random.random() < 0.3:
                replacement = random.choice(variations)
                title = title.replace(original, replacement, 1)
                break
        
        return title
    
    def _transform_content(self, content: str, index: int) -> str:
        """本文コンテンツの変換"""
        # ステップ1: 語彙多様化
        content = self._diversify_vocabulary(content)
        
        # ステップ2: 専門用語の変換
        content = self._transform_technical_terms(content)
        
        # ステップ3: 学術的表現の変換
        content = self._transform_academic_expressions(content)
        
        # ステップ4: 文構造の複雑化
        content = self._complexify_syntax(content, index)
        
        # ステップ5: 文章長の変動
        content = self._vary_sentence_length(content)
        
        # ステップ6: 接続表現の多様化
        content = self._diversify_connectives(content)
        
        # ステップ7: 一貫性の維持
        content = self._maintain_coherence(content)
        
        return content
    
    def _diversify_vocabulary(self, text: str) -> str:
        """語彙を多様化する"""
        result = text
        
        # 同義語の置換（段階的に）
        for original, synonyms in self.synonym_mapping.items():
            count = result.count(original)
            if count > 0:
                # 同じ語が複数回出現する場合、一部のみ置換
                replacement_count = min(count, random.randint(1, max(1, count // 2)))
                for _ in range(replacement_count):
                    if random.random() < 0.6:
                        replacement = random.choice(synonyms)
                        result = result.replace(original, replacement, 1)
        
        return result
    
    def _transform_technical_terms(self, text: str) -> str:
        """専門用語を変換する"""
        result = text
        
        for original, variations in self.technical_variations.items():
            if original in result and random.random() < 0.4:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def _transform_academic_expressions(self, text: str) -> str:
        """学術的表現を変換する"""
        result = text
        
        for original, variations in self.academic_expressions.items():
            if original in result and random.random() < 0.3:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def _complexify_syntax(self, text: str, index: int) -> str:
        """文構造を複雑化する"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
                
            # 長い文に装飾を追加
            if len(sentence) > 50 and random.random() < 0.25:
                # hedging languageの追加
                if random.random() < 0.7:
                    pattern = random.choice(self.hedging_patterns)
                    sentence = pattern.format(sentence)
            
            # 中程度の文に視点マーカーを追加
            elif 30 < len(sentence) < 100 and random.random() < 0.15:
                if random.random() < 0.5:
                    marker = random.choice(self.personal_markers)
                    sentence = marker + "、" + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _vary_sentence_length(self, text: str) -> str:
        """文章長を変動させる"""
        sentences = re.split(r'(?<=[。！？])', text)
        modified_sentences = []
        
        for sentence in sentences:
            if not sentence.strip():
                continue
                
            # 非常に長い文を分割（120文字以上）
            if len(sentence) > 120 and random.random() < 0.3:
                parts = sentence.split('、')
                if len(parts) >= 4:
                    # 適切な位置で分割
                    mid_point = len(parts) // 2
                    first_part = '、'.join(parts[:mid_point]) + '。'
                    second_part = '、'.join(parts[mid_point:])
                    modified_sentences.extend([first_part, second_part])
                    continue
            
            # 短い文を詳細化（25文字以下）
            elif len(sentence) < 25 and random.random() < 0.2:
                detail_phrases = [
                    "このような背景を踏まえると、",
                    "さらに詳しく検討すると、",
                    "より具体的に述べるならば、",
                    "この点については、",
                    "特に注目すべきは、",
                    "この研究において、"
                ]
                if random.random() < 0.6:
                    detail = random.choice(detail_phrases)
                    sentence = detail + sentence
            
            modified_sentences.append(sentence)
        
        return ''.join(modified_sentences)
    
    def _diversify_connectives(self, text: str) -> str:
        """接続表現を多様化する"""
        result = text
        
        for original, variations in self.connective_variations.items():
            if original in result and random.random() < 0.4:
                replacement = random.choice(variations)
                result = result.replace(original, replacement, 1)
        
        return result
    
    def _maintain_coherence(self, text: str) -> str:
        """一貫性を維持する"""
        sentences = re.split(r'(?<=[。！？])', text)
        
        # 学術的な文体の維持
        for i, sentence in enumerate(sentences):
            if sentence.strip():
                # 敬語の除去
                sentence = sentence.replace('です。', 'である。')
                sentence = sentence.replace('ます。', 'る。')
                sentence = sentence.replace('でした。', 'であった。')
                sentence = sentence.replace('ました。', 'た。')
                
                # 文の整理
                sentence = re.sub(r'\s+', ' ', sentence)
                sentence = sentence.strip()
                
                sentences[i] = sentence
        
        return ''.join(sentences)
    
    def _postprocess_document(self, text: str) -> str:
        """文書の後処理"""
        # 連続する空白の除去
        text = re.sub(r'  +', ' ', text)
        
        # 改行の整理
        text = re.sub(r'\n\s*\n\s*\n', '\n\n', text)
        
        # 句読点の調整
        text = re.sub(r'([。！？])([、，])', r'\1', text)
        
        return text

def main():
    """メイン処理"""
    print("AI文章人間化処理 - 最終アウトプット生成")
    print("=" * 60)
    
    # ai-text.mdの読み込み
    try:
        with open('ai-text.md', 'r', encoding='utf-8') as f:
            original_content = f.read()
        print(f"✓ ai-text.mdを読み込みました（{len(original_content)}文字）")
    except FileNotFoundError:
        print("✗ ai-text.mdが見つかりません")
        return
    
    # 人間化処理の実行
    print("\n人間化処理を実行中...")
    pipeline = FinalHumanizationPipeline()
    
    # 処理実行
    humanized_content = pipeline.transform_document(original_content)
    
    # 結果の保存
    with open('humanized-ai-text.md', 'w', encoding='utf-8') as f:
        f.write(humanized_content)
    
    print(f"✓ 人間化処理完了（{len(humanized_content)}文字）")
    print("✓ humanized-ai-text.mdに保存しました")
    
    # 簡易統計
    original_sentences = len(re.findall(r'[。！？]', original_content))
    humanized_sentences = len(re.findall(r'[。！？]', humanized_content))
    
    print(f"\n処理結果統計:")
    print(f"  元の文字数: {len(original_content)}")
    print(f"  処理後文字数: {len(humanized_content)}")
    print(f"  文字数変化: {len(humanized_content) - len(original_content):+d}")
    print(f"  元の文数: {original_sentences}")
    print(f"  処理後文数: {humanized_sentences}")
    print(f"  文数変化: {humanized_sentences - original_sentences:+d}")
    
    # 変更点の分析
    print(f"\n主な変更点:")
    changes = []
    
    # 代表的な変更を特定
    if "生体組織" in humanized_content and "臓器" in original_content:
        changes.append("臓器 → 生体組織")
    if "機構" in humanized_content and "システム" in original_content:
        changes.append("システム → 機構")
    if "三次元" in humanized_content and "3D" in original_content:
        changes.append("3D → 三次元")
    if "興味深いことに" in humanized_content:
        changes.append("文章装飾の追加")
    
    for change in changes[:5]:
        print(f"  • {change}")
    
    print(f"\n✓ 人間化処理が完了しました！")
    print("humanized-ai-text.mdをご確認ください。")

if __name__ == "__main__":
    main() 