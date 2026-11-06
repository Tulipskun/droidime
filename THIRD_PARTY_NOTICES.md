# Third-party dictionary data

DroidIME packages word lists for offline keyboard suggestions. These are word lists, not full dictionary articles or definitions.

- **Thai Royal Institute Dictionary word list**: derived from the Royal Institute Dictionary B.E. 2554 and redistributed by [nv23/thai-wordlist](https://github.com/nv23/thai-wordlist). The upstream repository states 19,069 entries and provides the dataset under the MIT License. The official online dictionary is https://dictionary.orst.go.th.
- **Thai frequency data**: Thai National Corpus data packaged by [PyThaiNLP](https://github.com/PyThaiNLP/pythainlp), CC0.
- **English base vocabulary**: word frequencies from [wordfreq](https://github.com/rspeer/wordfreq), CC BY-SA 4.0.
- **English spelling vocabulary**: en_US word list from [SCOWL / Kevin Atkinson](https://wordlist.aspell.net/), obtained through the [ropensci/hunspell](https://github.com/ropensci/hunspell) package. SCOWL word lists may be used, copied, modified, distributed and sold provided the copyright and permission notice are retained in supporting documentation. The original source identifies the collective work as Copyright 2000–2018 Kevin Atkinson and includes additional Ispell notices. The dictionary source snapshot was updated from LibreOffice's English Hunspell dictionary.
- **English profanity vocabulary**: [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list), MIT License.
- **English contemporary slang vocabulary**: single-word entries extracted from [codeclawd/slanglate](https://github.com/codeclawd/slanglate), whose repository identifies the project as MIT licensed.

Only individual vocabulary terms are used in the keyboard assets; definitions, examples, and multiword phrases are not bundled. Offensive terms are included as ordinary vocabulary so the keyboard can recognize them; DroidIME does not censor typed text.
