"use client";
import dynamic from "next/dynamic";

import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import styles from "@/app/_components/markdown-styles.module.css";
import 'katex/dist/katex.min.css';
import { InlineMath, BlockMath } from 'react-katex';
import { JUNG_STIMULUS_WORDS } from "@/components/jung-word-assessment/JungWordTest";

import VectorVisualization from "@/components/spirit-in-physics/vector-visualization";
import CorrelationForm from "@/components/spirit-in-physics/correlation-form";
import { SpiritModel } from "@/components/spirit-in-physics/spirit-model";
import CorrelationFormWrapper from "@/components/spirit-in-physics/correlation-form-wrapper";
import TearableItems from "@/components/spirit-in-physics/TearableItems";
import FaceAnalysis from "@/components/spirit-in-physics/FaceEmotionAnalysis";
import Container from "@/app/_components/container";
import Alert from "@/app/_components/alert";
import Header from "@/app/_components/header";
import { PostHeader } from "@/app/_components/post-header";
import KawasakiModel from "@/components/kawasaki-model";
import JungWordTest from "@/components/jung-word-assessment/JungWordTest";
import { JungVoiceAssessment } from "@/components/jung-voice-assessment";

export default function Page() {
  const dimvs = Array(0).fill("");
  const [mandalaData, setMandalaData] = useState(dimvs); // State to hold Mandala data
  const [electron, setElectron] = useState(1);
  const [proton, setProton] = useState(1);
  const [neutron, setNeutron] = useState(1);

  const handleMandalaData = (mandalaData: any) => {
    let count = 0;
    for (let i = 0; i < mandalaData.length; i++) {
      if (mandalaData[i] !== "") {
        count++;
      }
    }
    console.log(count);
    setMandalaData(mandalaData);
    setElectron(count);
    setProton(count);
    setNeutron(count);
  };

  return (
    <main>
      <Alert preview={false} />
      <Container>
        <Header />
        <article className="mb-32">
          <PostHeader
            title={"Spirit in Physics"}
            coverImage={"/assets/posts/spirit-in-physics/cover.jpg"}
            date={"2024-11-30"}
            author={{
              name: "Jun Kawasaki(root@junkawasaki.com), Kazuki Tainaka, Tomonori Takeuchi",
              picture: "/assets/posts/authors/jk.jpg",
            }}
          />
          <div className="max-w-2xl mx-auto">
            <div className={styles["markdown"]}>
              <h1 className="text-2xl font-bold">Spirit in Physics</h1>
              
              <p>
                Jun Kawasaki(root@junkawasaki.com), Kazuki Tainaka, Tomonori Takeuchi;
                Graduate School of Medical and Dental Sciences, Niigata University, Brain Research Institute, Niigata University, Japan,
                Department of Biomedicine, Aarhus University, Denmark
              </p>

              <h2>Introduction: Structuring and Quantifying Human Spirit Using the Informational Vector Space</h2>
              
              <h3>Hypothesis 1</h3>
              <p>
                <strong>Information is Physics:</strong> Information is inherently physical—it obeys
                the laws of thermodynamics and directly influences energy
                exchange. Experimental validations of Landauer's principle (Bérut et
                al., 2012) reinforce that computation and energy are fundamentally
                intertwined.
              </p>

              <h3>Hypothesis 2</h3>
              <p>
                <strong>Self-expansiveness into information space:</strong> Based on the rubber
                hand illusion (Botvinick & Cohen, 1998), self-boundaries are not
                fixed but can extend to incorporate external objects. We assume
                that the neural mechanisms underlying multisensory integration—
                demonstrated by the rubber hand illusion—provide a measurable
                basis for transforming physical self-perception into an expansive,
                information-rich state that underpins Spirit.
              </p>

              <h2>Spirit Physical Space (Kawasaki Model)</h2>
              <h3>Mathematical Model:</h3>
              <div className={styles.mathBlock}>
                <BlockMath math="S = V, E, T" />
              </div>
              <div className={styles.mathBlock}>
                <BlockMath math="E = -\ln P(w_O | w_I),\; V = \{\vec{w_I}, \vec{w_O}, ...\},\; T = \text{time axis}." />
              </div>

              <h3>Physical Definition of Spirit:</h3>
              <div className={styles.mathBlock}>
                <BlockMath math="\psi(S) = \frac{\delta E(S)}{\delta S}." />
              </div>
              <p>(Bérut et al., 2012)</p>
            </div>
          </div>
        </article>
      </Container>
      
      {/* KawasakiModel - Full width outside of container */}
      <div className="w-full h-[80vh]">
        <KawasakiModel />
      </div>
      
      <Container>
        <article className="pt-48 mb-32">
          <div className="max-w-2xl mx-auto">
            <div className={styles["markdown"]}>
              <h2>Vectorization Spirit Using the Word Association Experiment (Jung, 1910)</h2>
              <div className={styles.mathBlock}>
                <BlockMath math="P(w_O | w_I) = \frac{\exp(\vec{w_I} \cdot \vec{w_O}) \cdot [r(w_I, w_O)]^{\alpha} \cdot \exp(\gamma \frac{\Delta SP(w_I,w_O)}{\lambda}) \cdot \exp(\eta F(w_I, w_O))}{\sum_{j} \exp(\vec{w_I} \cdot \vec{w_j}) \cdot [r(w_I, w_j)]^{\alpha} \cdot \exp(\gamma \frac{\Delta SP(w_I,w_j)}{\lambda}) \cdot \exp(\eta F(w_I, w_j))}" />
              </div>

              <p><strong>Words(100):</strong></p>
              <div className="flex flex-wrap gap-2">
                {JUNG_STIMULUS_WORDS.join(", ")}
              </div>

              <p>
                <strong>Conventional Word2Vec:</strong> Quantify the strength of association using the
                inner product of word vectors.
              </p>
              <p>
                <strong>Jung's association method element:</strong> Introduce a factor that is the
                inverse of reaction time.
              </p>
              <p>
                <strong>Integrated model:</strong> Adjust the reaction speed factor with the
                hyperparameter \alpha and define a modified probability function as
                follows:
              </p>


              <main className="flex min-h-screen flex-col items-center justify-center p-24 bg-gradient-to-r from-blue-100 to-purple-100">
                <JungVoiceAssessment numberOfWords={10} />
              </main>

              <h2>Measurement via Emotion Analytics (Quantitative Analysis)</h2>
              <div className={styles.mathBlock}>
                <BlockMath math="r(w_I, w_O) = \frac{1}{T(w_I, w_O) + \epsilon}" />
              </div>
              
              <p>
                The facial recognition system defines an emotion score F(w_I, w_O)
                obtained from the subject's facial expression.
                This score is treated as an integrated index of the intensity of each
                emotion, such as "happiness," "sadness," and "surprise."
              </p>

              <h2>Measurement via Skin Potential Using the Rubber Hand Illusion(Qualitative Analysis)</h2>
              <div className={styles.mathBlock}>
                <BlockMath math="s(w_I, w_O) = \exp\left(\frac{\Delta SP(w_I, w_O)}{\lambda}\right)" />
              </div>
              
              <h2>Conclusion</h2>
              <p>
                <strong>Self-expansiveness is Spirit:</strong> Self-expansiveness plays a key role in shaping
                personal, physiological, and societal phenomena.
              </p>
              <p>
                <strong>Spirit Transformer Model:</strong> The Kawasaki Model proves that the spirit can be
                structured, measured, and quantified as dynamic physical information.
              </p>
              <div className={styles.mathBlock}>
                <BlockMath math="P(w_O | w_I) = \frac{\exp(\vec{w_I} \cdot \vec{w_O}) \cdot [r(w_I, w_O)]^{\alpha} \cdot \exp(\gamma \frac{\Delta SP(w_I,w_O)}{\lambda}) \cdot \exp(\eta F(w_I, w_O))}{\sum_{j} \exp(\vec{w_I} \cdot \vec{w_j}) \cdot [r(w_I, w_j)]^{\alpha} \cdot \exp(\gamma \frac{\Delta SP(w_I,w_j)}{\lambda}) \cdot \exp(\eta F(w_I, w_j))}" />
              </div>

              <h2>Results</h2>
              <p>Spirit in Physics (Jung's Word Association Test Embedding Model)</p>
              <Image
                src="/assets/posts/spirit-in-physics/241203-2.png"
                alt="Results visualization"
                width={500}
                height={500}
              />
              <p>https://www.junkawasaki.com/posts/spirit-in-physics</p>

              <h2>References</h2>
              <p>
                1. Landauer, R. (1991). Information is physical. Physics Today, 44(5), 23–29. 
                2. Bérut, A., Arakelyan, A., Petrosyan, A., Ciliberto, S., Dillenschneider, R., & Lutz, E. (2012). Experimental verification of Landauer's principle linking information and thermodynamics. Nature, 483(7388), 187–189. 
                3. Botvinick, M., & Cohen, J. (1998). Rubber-hand illusion. Nature, 391, 756. 
                4. Toyabe, S., Sagawa, T., Ueda, M., Muneyuki, E., & Sano, M. (2010). Experimental demonstration of information-to-energy conversion and validation of the generalized Jarzynski equality. Nature Physics, 6, 988–992.
              </p>


              <h3>Another Research / High-IQ Japanese GWAS: Explore IQ Genes</h3>
              <p>
                Leveraging Japan's unique genetics, a GWAS targeting individuals
                with IQ ≥140 will compare genetic and cognitive data to identify SNPs
                linked to intelligence. The study begins in 2024 with results slated for
                publication.
              </p>
              <p>
                Dataset: 92 people / CAMS IQ140 sd15 - IQ180t / SNPs.
              </p>
              <p>
                ref: Jonathan R. I. Coleman et al, Mol Psychiatry 24, 182-197 (2019)
              </p>
            </div>
          </div>
        </article>
      </Container>
    </main>
  );
}
