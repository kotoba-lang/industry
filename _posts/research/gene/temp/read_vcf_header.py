import gzip

vcf_path = 'manuscript/data/Savage2018_Intelligence_GWAS_European_OpenGWAS.vcf.gz'

try:
    with gzip.open(vcf_path, 'rt') as f:
        for i, line in enumerate(f):
            if i >= 200:
                break
            print(line.strip())
except FileNotFoundError:
    print(f"Error: File not found at {vcf_path}")
except Exception as e:
    print(f"An error occurred: {e}") 