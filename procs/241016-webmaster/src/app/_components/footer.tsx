import Container from "@/app/_components/container";

export function Footer() {
  return (
    <footer className="border-t border-neutral-200 dark:bg-slate-800 dark:text-white">
      <Container>
        <div className="py-28 flex flex-col lg:flex-row items-center">
          <h3 className="text-4xl lg:text-[2.5rem] font-bold tracking-tighter leading-tight text-center lg:text-left mb-10 lg:mb-0 lg:pr-4 lg:w-1/2">
            All rights reserved by Jun Kawasaki. @CC BY-NC-SA
          </h3>
        </div>
      </Container>
    </footer>
  );
}

export default Footer;
