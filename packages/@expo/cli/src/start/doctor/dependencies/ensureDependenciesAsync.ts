import { ExpoConfig, getConfig } from '@expo/config';
import chalk from 'chalk';
import wrapAnsi from 'wrap-ansi';

import { getMissingPackagesAsync, ResolvedPackage } from './getMissingPackages';
import { installAsync } from '../../../install/installAsync';
import * as Log from '../../../log';
import { CommandError } from '../../../utils/errors';
import { isInteractive } from '../../../utils/interactive';
import { logNewSection } from '../../../utils/ora';
import { confirmAsync } from '../../../utils/prompts';

export type EnsureDependenciesOptions = {
  /** The packages and/or version ranges that should be enforced in the project */
  requiredPackages: ResolvedPackage[];
  /** The user-facing message when the required packages are missing or incorrect */
  installMessage: string;
  /** The user-facing message when users aborted the installation */
  warningMessage: string;
  /** A previously loaded Expo configuration (loads when omitted) */
  exp?: ExpoConfig;
  /** If the prompts asking users to install should be skipped (defaults to false, in CI defaults to true) */
  skipPrompt?: boolean;
  /** Project can be mutated in the current environment (defaults to true, in CI defaults to false) */
  isProjectMutable?: boolean;
};

export async function ensureDependenciesAsync(
  projectRoot: string,
  {
    exp = getConfig(projectRoot).exp,
    requiredPackages,
    warningMessage,
    installMessage,
    // Don't prompt in CI
    skipPrompt = !isInteractive(),
    isProjectMutable = isInteractive(),
  }: EnsureDependenciesOptions
): Promise<boolean> {
  const { missing } = await getMissingPackagesAsync(projectRoot, {
    sdkVersion: exp.sdkVersion,
    requiredPackages,
  });
  if (!missing.length) {
    return true;
  }

  // Prompt to install or bail out...
  const readableMissingPackages = missing
    .map(({ pkg, version }) => (version ? [pkg, version].join('@') : pkg))
    .join(', ');

  let title = installMessage;

  if (skipPrompt && !isProjectMutable) {
    title += '\n\n';
  } else {
    let confirm = skipPrompt;
    if (skipPrompt) {
      // Automatically install packages without prompting.
      Log.log(wrapForTerminal(title + ` Installing ${chalk.cyan(readableMissingPackages)}`));
    } else {
      confirm = await confirmAsync({
        message: wrapForTerminal(
          title + ` Would you like to install ${chalk.cyan(readableMissingPackages)}?`
        ),
        initial: true,
      });
    }

    if (confirm) {
      // Format with version if available.
      const [packages, devPackages] = missing.reduce(
        ([deps, devDeps], p) => {
          const pkg = p.version ? [p.pkg, p.version].join('@') : p.pkg;
          if (p.dev) {
            return [deps, devDeps.concat(pkg)];
          }
          return [deps.concat(pkg), devDeps];
        },
        [[], []] as [string[], string[]]
      );

      if (packages.length) {
        await installPackagesAsync(projectRoot, {
          packages,
        });
      }

      if (devPackages.length) {
        await installPackagesAsync(projectRoot, {
          packages: devPackages,
          dev: true,
        });
      }

      // Try again but skip prompting twice, simply fail if the packages didn't install correctly.
      return await ensureDependenciesAsync(projectRoot, {
        skipPrompt: true,
        installMessage,
        warningMessage,
        requiredPackages,
      });
    }

    // Reset the title so it doesn't print twice in interactive mode.
    title = '';
  }

  const installCommand = 'npx expo install ' + missing.map(({ pkg }) => pkg).join(' ');

  const disableMessage = warningMessage;

  const solution = `Install ${chalk.bold(
    readableMissingPackages
  )} by running:\n\n  ${chalk.reset.bold(installCommand)}\n\n`;

  // This prevents users from starting a misconfigured JS or TS project by default.
  throw new CommandError(wrapForTerminal(title + solution + disableMessage + '\n'));
}

/**  Wrap long messages to fit smaller terminals. */
function wrapForTerminal(message: string): string {
  return wrapAnsi(message, process.stdout.columns || 80);
}

/** Create the bash install command from a given set of packages and settings. */
export function createInstallCommand({
  packages,
}: {
  packages: {
    file: string;
    pkg: string;
    version?: string | undefined;
  }[];
}) {
  return 'npx expo install ' + packages.map(({ pkg }) => pkg).join(' ');
}

/** Install packages in the project. */
async function installPackagesAsync(
  projectRoot: string,
  { packages, dev }: { packages: string[]; dev?: boolean }
) {
  const packagesStr = chalk.bold(packages.join(', '));
  Log.log();
  const installingPackageStep = logNewSection(`Installing ${packagesStr}`);
  try {
    await installAsync(packages, { projectRoot, dev });
  } catch (e: any) {
    installingPackageStep.fail(`Failed to install ${packagesStr} with error: ${e.message}`);
    throw e;
  }
  installingPackageStep.succeed(`Installed ${packagesStr}`);
}
