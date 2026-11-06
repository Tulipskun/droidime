# Third-party dictionary data

DroidIME packages word lists for offline keyboard suggestions. These assets contain vocabulary terms and frequencies, not full dictionary articles or definitions.

## Data sources

- **Thai Royal Institute Dictionary word list**: derived from the Royal Institute Dictionary B.E. 2554 and redistributed by [nv23/thai-wordlist](https://github.com/nv23/thai-wordlist). Its README states 19,069 entries and its repository is MIT-licensed. The official online dictionary is https://dictionary.orst.go.th. DroidIME merges these words with the Thai National Corpus frequencies already used by the app.
- **Thai frequency data**: Thai National Corpus data packaged by [PyThaiNLP](https://github.com/PyThaiNLP/pythainlp), CC0.
- **English frequency data**: [wordfreq](https://github.com/rspeer/wordfreq), CC BY-SA 4.0.
- **English spelling vocabulary**: en_US dictionary derived from [SCOWL](https://wordlist.aspell.net/) and distributed in the [ropensci/hunspell](https://github.com/ropensci/hunspell) package. The source dictionary snapshot identifies the upstream English Hunspell dictionary as SCOWL-derived. Only individual word entries are included; the Hunspell affix rules and library are not included.
- **English profanity vocabulary**: single-word entries from [dsojevic/profanity-list](https://github.com/dsojevic/profanity-list), MIT License.
- **English contemporary slang vocabulary**: single-word keys from [codeclawd/slanglate](https://github.com/codeclawd/slanglate). Its README identifies the project as MIT licensed. Multiword expressions, definitions, and examples are not bundled.

Offensive terms are included as ordinary vocabulary so the keyboard can recognize them. DroidIME does not censor typed text.

## SCOWL word-list notice

The en_US word list is derived from SCOWL. The upstream LibreOffice English dictionary README attributes the collective word-list work to Kevin Atkinson and grants the following permission:

Copyright 2000-2018 by Kevin Atkinson

Permission to use, copy, modify, distribute and sell these word lists, the associated scripts, the output created from the scripts, and its documentation for any purpose is hereby granted without fee, provided that the above copyright notice appears in all copies and that both that copyright notice and this permission notice appear in supporting documentation. Kevin Atkinson makes no representations about the suitability of this array for any purpose. It is provided "as is" without express or implied warranty.

The upstream dictionary also credits Alan Beale for contributions to 12Dicts and ENABLE.

## MIT license notices

### Thai word list

Copyright (c) 2015 Pakkapon Phongtawee

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### English profanity list

Copyright (c) 2021 David Sojevic

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### English slang list

The [slanglate repository](https://github.com/codeclawd/slanglate) identifies its project as MIT licensed. The vocabulary keys are used as individual words only; the upstream repository contains the corresponding license statement in its README.
