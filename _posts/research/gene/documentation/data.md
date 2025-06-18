gftdを通して得られた高IQ者集団のゲノムデータ(91名)と、			
ジーンクエスト、ユーグレナマイヘルスの遺伝子検査サービスユーザーから得られたゲノムデータ(約4万人)の比較を行った。			
詳細には、caseをgftdを通して得られた高IQ者集団、controlをユーザー集団としてGWAS(ゲノムワイド関連解析)を実施。			
ゲノムデータのQC(品質管理、Quality Control)			
variantに関するQC: 			
variantあたりのcall rate<5%		
minor allele frequency < 1%		
HWEのp値 < 0.000001		
常染色体以外		
上記いずれかに当てはまるvariantは除外		
sampleに関するQC: 			
sampleあたりのcall rate < 95%		
アンケートとゲノムデータの性別の不一致		
血縁関係のペアの片方 (PI_HAT>0.1875)		
ゲノムから見た日本人集団クラスタに属さない		
GWAS方法			
関連解析の手法はロジスティック回帰分析で行っている。			
また、共変数として、性別とゲノムデータから計算した主成分を1から10まで加えている。			
また、過去に行われている大規模IQ GWASの以下の論文結果とも比較している。			
Coleman JRI, Bryois J, Gaspar HA, et al. Biological annotation of genetic loci associated with intelligence in a meta-analysis of 87,740 individuals. Mol Psychiatry. 2019;24(2):182-197. doi:10.1038/s41380-018-0040-6			
QC後の人数や各値の分布については以下の通り。			
colID	option	control	case
N	N	41528	91
性別	男性	20300	85
性別	女性	21228	6
PC1	mean	1.25E-07	-5.68E-05
PC1	std	0.0049034	0.0041001
PC2	mean	-3.77E-07	0.0001719
PC2	std	0.0049028	0.0044261
PC3	mean	-9.03E-07	0.000412
PC3	std	0.0049029	0.0043508
PC4	mean	-6.37E-08	2.91E-05
PC4	std	0.0049018	0.0049168
PC5	mean	-2.38E-07	0.0001088
PC5	std	0.0049016	0.0049644
PC6	mean	-1.29E-06	0.0005885
PC6	std	0.0049027	0.0044021
PC7	mean	-1.04E-06	0.0004732
PC7	std	0.0049022	0.0046889
PC8	mean	1.45E-06	-0.00066
PC8	std	0.0048999	0.0056536
PC9	mean	1.37E-07	-6.25E-05
PC9	std	0.0049019	0.004853
PC10	mean	1.40E-06	-0.000638
PC10	std	0.004901	0.0052174