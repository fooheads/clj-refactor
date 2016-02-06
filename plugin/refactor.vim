noremap crad :CAddDeclaration<CR>
noremap cram :CAddMissingLibSpec<CR>
noremap crcc :CCycleColl<CR>
noremap crci :CCycleIf<CR>
noremap crcp :CCyclePrivacy<CR>
noremap crct :CCycleThread<CR>
noremap crel :CExpandLet<CR>
noremap cref :CExtractFunction 
noremap crfe :CFunctionFromExample<CR>
noremap cril :CIntroduceLet 
noremap crml :CMoveToLet 
noremap crtf :CThreadFirstAll<CR>
noremap crth :CThread<CR>
noremap crtl :CThreadLastAll<CR>
noremap crtt :CThreadLast<CR>
noremap crua :CUnwindAll<CR>
noremap cruw :CUnwindThread<CR>

noremap crcn :CCleanNS<CR>
noremap crrd :CRenameDir 
noremap crrf :CRenameFile 
noremap crrs :CRenameSymbol 

inoremap / /<ESC>:silent! CMagicRequires<CR>a
