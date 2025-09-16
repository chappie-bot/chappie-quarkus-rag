package org.chappie.bot.rag;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.enterprise.context.ApplicationScoped;
import static picocli.CommandLine.Command;

@TopCommand
@Command(
  name = "docs",
  mixinStandardHelpOptions = true,
  subcommands = {
      FindCommand.class,
      ManifestEnrichCommand.class,
      BakeImageCommand.class
  },
  description = "RAG helper CLI for Quarkus docs"
)
@ApplicationScoped
public class DocsCli implements Runnable {
  @Override 
  public void run() { /* prints help by default */ }
}

