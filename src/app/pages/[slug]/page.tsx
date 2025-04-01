"use client";
import dynamic from "next/dynamic";

import Image from "next/image";
import Link from "next/link";
import { useState } from "react";
import styles from "@/app/_components/markdown-styles.module.css";

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
              name: "Jun Kawasaki, Kazuki Tainaka, Takeuchi Tomonori",
              picture: "/assets/posts/authors/jk.jpg",
            }}
          />
          <div className="max-w-2xl mx-auto">
            <div className={styles["markdown"]}>
              <h1 className="text-2xl font-bold">Spirit in Physics</h1>
              <h2>Abstract</h2>
              <p>
                Spirit, a concept deeply rooted in human consciousness, has been
                traditionally unquantifiable in classical physical sciences.
                This study introduces the Kawasaki Model, which conceptualizes
                spirit as a vector space, allowing for its measurement and
                structuring analysis through physics empirical methods.
              </p>
              <p>
                The research employs skin potential measurements and emotion
                analysis to investigate Spirit's physics structure and dynamics,
                inspired by the Rubber-Hand, Marble-Hand illusions and
                Traditional Buddhism Mandala Method. Participants are
                vectorizing conceptual structures using word association experiments based on Jung's methods (1910).
                They are exposed to physical and digital stimuli as they interact
                with conceptual separations and integrations, observing changes
                in skin potential and facial expressions. These responses are
                timestamped and analyzed, revealing the impact of
                self-expansiveness—spirituality's core property—on both
                physicality and cognition.
              </p>
              <p>
                Results demonstrate that spirit can be quantitatively measured
                and represented as vectors in an information space. The study
                finds that self-expansiveness influences individual behavior,
                societal structures, and even physiological states, acting as
                energy and mass in physical terms.
              </p>
              <p>
                This novel approach provides a foundation for integrating spirit
                into physical sciences, with potential applications in
                understanding mental health, social dynamics, and personal
                growth. Future work will validate and expand these findings to
                ensure reproducibility and broader applicability.
              </p>
              <p>
                Keywords: spirit, Kawasaki Model, vector space,
                self-expansiveness, skin potential, emotion analysis, well-being
              </p>
              <h2>Introduction</h2>
              <p>
                What are the characteristics that distinguish humans from other
                animals?
              </p>
              <p>
                In Japanese, humans are referred to as primates, and spirit has
                been regarded as having specific qualities. However, spirit has
                not been measured or quantified as a physical science.
              </p>
              <p>
                Information is inherently physical—it obeys the laws of thermodynamics and 
                directly influences energy exchange. Experimental validations of Landauer's 
                principle (Bérut et al., 2012) reinforce that computation and energy are 
                fundamentally intertwined.
              </p>
              <p>
                This research refers to the following paper and proposes to
                measure and quantify the structure of spirit using physical
                methods as the Kawasaki Model.
              </p>
              <Link
                href="https://www.nature.com/articles/35784"
                className="underline text-block"
                target="_blank"
              >
                Matthew Botvinick & Jonathan Cohen (1998). Rubber hands 'feel'
                touch that eyes see
              </Link>
              <p></p>
              <Link
                href="https://pmc.ncbi.nlm.nih.gov/articles/PMC3125296/"
                className="underline text-block"
                target="_blank"
              >
                Marieke Rohde, Massimiliano Di Luca, Marc O Ernst. (2011). The
                Rubber Hand Illusion: Feeling of Ownership and Proprioceptive
                Drift Do Not Go Hand in Hand
              </Link>
              <p></p>
              <Link
                href="https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0091688"
                className="underline text-block"
                target="_blank"
              >
                Senna, I., Maravita, A., Bolognini, N., & Parise, C. V. (2014).
                The Marble-Hand Illusion
              </Link>
              <p>
                This model allows for the observation and measurement of spirit,
                which has not been addressed in physical science until now.
              </p>
              <h2>Structure of Spirit (Spirit in Physics: Kawasaki Model)</h2>
              <p>The core of the model is that Spirits are Vectors.</p>
              <p>
                By representing spirit as vectors, it becomes possible to
                measure human consciousness and unconsciousness and to
                facilitate their transformation.
              </p>
              <p>
                These have traditionally been treated as mandala methods in
                Japanese Buddhism.
              </p>
              <h3>Physical Definition of Spirit</h3>
              <p>Basic structure of spirit: Similar to vector spaces</p>
              <p>Basic properties of spirit: Similar to information</p>
              <p>Dynamic structure of spirit: Similar to Bohr's atomic model</p>
              <p>Interactions of spirit: Similar to molecular interactions</p>
              <p>Characteristics of spirit: Spirit acts on physicality</p>
              <p>
                Energy quantity of spirit: It has been proven that information
                possesses energy.
              </p>
              <p>Spirituality meaning is understand spirit.</p>
              <p>
                Based on the rubber hand illusion (Botvinick & Cohen, 1998), self-boundaries 
                are not fixed but can extend to incorporate external objects. We assume that 
                the neural mechanisms underlying multisensory integration—demonstrated by the 
                rubber hand illusion—provide a measurable basis for transforming physical 
                self-perception into an expansive, information-rich state that underpins Spirit.
              </p>
              <Link href="https://www.nature.com/articles/nphys1821">
                - Toyabe, S., Sagawa, T., Ueda, M., Muneyuki, E., & Sano, M.
                (2010). [Experimental demonstration of information-to-energy
                conversion and validation of the generalized Jarzynski
                equality](https://www.nature.com/articles/nphys1821).
              </Link>
              <h3>Mathematical Model</h3>
              <p>
                Conventional Word2Vec: Quantify the strength of association using the inner product of word vectors.
              </p>
              <p>
                Jung's association method element: Introduce a factor that is the inverse of reaction time.
              </p>
              <p>
                Integrated model: Adjust the reaction speed factor with the hyperparameter \(\alpha\) and 
                define a modified probability function.
              </p>
              <p>
                \(\lambda\): Scale adjustment constant
              </p>
              <h3>Experimental Methods for Measuring Spirit</h3>
              <p>
                To measure spirit, the information space of individuals is
                vectorized.
              </p>

              <h3>Structuring Spirit</h3>
              <p>
                Using the Mandala method and Word Association Experiment (Jung, 1910), 
                the information space of individuals is vectorized.
              </p>
              <main className="flex min-h-screen flex-col items-center justify-center p-24 bg-gradient-to-r from-blue-100 to-purple-100">
                <JungWordTest />
              </main>

              <p>Basic structure of spirit: Similar to vector spaces</p>
              <div className="w-full overflow-hidden">
                <KawasakiModel />
              </div>
              <p>
                Individuals judge the commonality between concepts presented on
                the screen as either close or distant.
              </p>
              <p>
                Based on the response results, a vector space coordinate is
                created for the concepts between individuals.
              </p>
              <h3>Measuring Spirit</h3>
              <p>
                The vectorized concepts are presented on the screen, and visual
                effects are used to separate them.
              </p>
              <p>
                The changes in skin potential due to these effects are measured.
              </p>
              <p>
                By measuring the range of effects of the concepts, the range of
                effects of spirit can be measured.
              </p>
              <h3>Measurement Using Skin Potential (Qualitative Research)</h3>
              <p>Measurement is conducted using skin potential.</p>
              <p>
                While presenting the separation and integration of concepts on
                the screen, participants are shown expressions of separation or
                integration on the screen.
              </p>
              <p>
                Changes in skin potential during these actions are measured.
              </p>
              <p>The measurement of skin potential uses SKINPRO.</p>
              <p>
                Concept measurements are timestamped, and aligned with the
                timestamps of the skin potential measurement device, analyzing
                the changes in skin potential and the characteristics during the
                separation and integration of concepts.
              </p>
              <p>
                In the Rubber hand illusion, skin potential changed as shown in
                the graphs.
              </p>
              <p>
                Similar results were obtained in the analysis of the structure
                of spirit through concept measurement (currently a hypothesis).
              </p>

              <Image
                src="/assets/posts/spirit-in-physics/241203-1.png"
                alt="SKINPRO"
                width={500}
                height={500}
              />
              <h3>Measurement Using Emotion Analysis (Quantitative Research)</h3>
              <p>Facial expressions are measured using a camera.</p>
              <p>
                The facial recognition system defines an emotion score F(w_I, w_O) obtained from 
                the subject's facial expression. This score is treated as an integrated index 
                of the intensity of each emotion, such as "happiness," "sadness," and "surprise."
              </p>
              <p>
                While presenting the separation and integration of concepts on
                the screen, participants are shown expressions of separation or
                integration on the screen.
              </p>
              <p>
                The separation of the above concepts is presented on the screen
                while using a camera to measure the participants' facial
                expressions.
              </p>
              <p>
                Changes in the participants' facial expressions during the
                separation and integration of concepts are measured.
              </p>
              <p>
                Emotion analysis in the Mar hand illusion changed as shown in
                the graphs.
              </p>
              <p>
                Similar results were obtained in the analysis of the structure
                of spirit through concept measurement (currently a hypothesis).
              </p>
              <h3>Measurement Results</h3>
              <p>Experimental results - n+1 (2024/12/03)</p>
              <Image
                src="/assets/posts/spirit-in-physics/241203-2.png"
                alt="SKINPRO"
                width={500}
                height={500}
              />
              <h3>Conclusion</h3>
              <p>
                The research demonstrated that it is possible to measure and
                quantify the structure of human spirit using physical methods.
              </p>
              <p>
                Self‑expansiveness is Spirit: Self‑expansiveness plays a key role in shaping 
                personal, physiological, and societal phenomena.
              </p>
              <p>
                Spirit Transformer Model: The Kawasaki Model proves that the spirit can be 
                structured, measured, and quantified as dynamic physical information.
              </p>
              <p>
                From these results, it was found that spirit is
                self-expansiveness.
              </p>
              <p>It was found that self-expansiveness changes.</p>
              <p>
                It was found that the body is influenced by self-expansiveness.
              </p>
              <p>
                It was found that the mind is influenced by self-expansiveness.
              </p>
              <p>It was found that society is formed by self-expansiveness.</p>
              <p>
                It was found that self-expansiveness can be represented in a
                Vector DB.
              </p>
              <h3>Applied Research</h3>
              <p>Understanding the self based on self-expansiveness</p>
              <p>Explaining mental pain based on self-expansiveness</p>
              <p>
                Explaining the pain of social structures based on
                self-expansiveness
              </p>
              <p>Realizing the self based on self-expansiveness</p>
              <p>
                This research has shown that spirit can be structured, measured,
                and quantified.
              </p>
              <p>
                It has also been shown that individual spirit can be represented
                in vector space based on measurements.
              </p>
              <p>
                Spirit interacts as a vector of information space in physical
                space, and information acts as mass and energy in physical
                space, affecting the physicality of individuals.
              </p>
              <p>
                Using Buddhist terminology, this is non-separation of self and
                others.
              </p>
              <p>
                This research conducts measurements and quantifications of human
                spirit based on the Mar hand illusion experiment.
              </p>
              <h3>Another Research / High-IQ Japanese GWAS</h3>
              <p>
                Leveraging Japan's unique genetics, a GWAS targeting individuals with IQ ≥140 
                will compare genetic and cognitive data to identify SNPs linked to intelligence. 
                The study begins in 2024 with results slated for publication.
              </p>
              <p>
                Dataset: 92 people / CAMS IQ140 sd15 - IQ180t / SNPs.
              </p>
              <p>
                Reference: Jonathan R. I. Coleman et al, Mol Psychiatry 24, 182–197 (2019)
              </p>
              <h3>Consciousness and unconsciousness (conscious)</h3>
              <h3>Ensuring Reproducibility</h3>
              <p>
                [x] Empirical experiment: Observing changes in skin potential
                due to the action of a physical Mar hand = Responds when the Mar
                hand is tapped
              </p>
              <p>
                [ ] Empirical experiment: Observing changes in skin potential
                due to the action of a digitally represented Mar hand
                (physicality) = No prior research
              </p>
              <p>
                [ ] Empirical experiment: Observing changes in skin potential
                due to the action of digitally represented concepts = No prior
                research
              </p>
              <p>
                [ ] Empirical experiment: Exploring the range of effects of
                concepts by measuring skin potential = No prior research
              </p>
              <p>
                [ ] Empirical experiment: Exploring the range of self-expansion
                (spirituality) by visualizing the range of effects of concepts =
                No prior research
              </p>
              <p>
                [ ] Empirical experiment: Transforming self-concept
                (spirituality) by acting on the range of effects of concepts =
                No prior research
              </p>
              <h3>You Can Try Now</h3>
              <p>
                Spirit in Physics (Emotional Analytics)
              </p>
              <p>
                Niigata Univ IRB
                Approved at 2025/03
              </p>
              <h2>References</h2>
              <p>
                1. Landauer, R. (1991). Information is physical. Physics Today, 44(5), 23–29.
              </p>
              <p>
                2. Bérut, A., Arakelyan, A., Petrosyan, A., Ciliberto, S., Dillenschneider, R., & Lutz, E. (2012). 
                   Experimental verification of Landauer's principle linking information and thermodynamics. 
                   Nature, 483(7388), 187–189.
              </p>
              <p>
                3. Botvinick, M., & Cohen, J. (1998). Rubber-hand illusion. Nature, 391, 756.
              </p>
              <p>
                4. Toyabe, S., Sagawa, T., Ueda, M., Muneyuki, E., & Sano, M. (2010). 
                   Experimental demonstration of information-to-energy conversion and validation 
                   of the generalized Jarzynski equality. Nature Physics, 6, 988–992.
              </p>
              <p>
                5. Senna, I., Maravita, A., Bolognini, N., & Parise, C. V. (2014).
                [The Marble-Hand
                Illusion](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0091688).
              </p>
              <p>
                6. Tsakiris, M., & Haggard, P. (2005). The Rubber Hand Illusion
                Revisited: Visuotactile Integration and Self-Attribution.
                Journal of Experimental Psychology: Human Perception and
                Performance, 31(1), 80–91.
                https://psycnet.apa.org/doiLanding?doi=10.1037%2F0096-1523.31.1.80
              </p>
              <p>
                7. Jonathan R. I. Coleman et al, Mol Psychiatry 24, 182–197 (2019)
              </p>
              <p>
                8. 1994, Skin Conductance and Skin Potential Responses to
                Emotion-Inducing Pictures, Cacioppo, J.T., Psychophysiology,
                3.286, 100+
              </p>
              <p>
                9. 2000, The Psychophysiology of Emotion: The Role of the Skin
                Conductance Response, Critchley, H.D., Neuroscience and
                Biobehavioral Reviews, 8.802, 200+
              </p>
              <p>
                10. 2005, Emotion Regulation and the Skin Conductance Response: The
                Role of Cognitive Reappraisal, Gross, J.J., Journal of
                Personality and Social Psychology, 7.673, 150+
              </p>
              <p>
                11. 2010, The Impact of Emotion on Skin Conductance Response: A
                Meta-Analysis, Kreibig, S.D., Biological Psychology, 4.152, 120+
              </p>
              <p>
                12. 2015, Skin Conductance Responses to Emotional Stimuli: A Review
                of the Literature, Boucsein, W., International Journal of
                Psychophysiology, 2.882, 80+
              </p>
              <h2>Version</h2>
              <p>2024/11/30 : 0.1.0</p>
              <p>2024/12/03 : 0.2.0</p>
              <p>2024/12/10 : 0.3.0</p>
            </div>
          </div>
        </article>
      </Container>
    </main>
  );
}
